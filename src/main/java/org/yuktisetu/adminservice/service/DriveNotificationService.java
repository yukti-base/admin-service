package org.yuktisetu.adminservice.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yuktisetu.core.time.IstTime;
import org.yuktisetu.drive.model.PostingNotice;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.db.PostingTarget;
import org.yuktisetu.drive.repository.PostingEligibleStudentRepository;
import org.yuktisetu.drive.repository.PostingTargetRepository;
import org.yuktisetu.identity.model.NotifyTarget;
import org.yuktisetu.identity.model.RoleType;
import org.yuktisetu.identity.repository.UserRoleAssignmentRepository;
import org.yuktisetu.messaging.events.Dispatches;
import org.yuktisetu.messaging.events.DriveTopics;
import org.yuktisetu.messaging.events.NotificationDispatchEvent;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * admin-service's notification producer.
 *
 * Every method here writes outbox rows and returns. Nothing sends mail, nothing
 * calls notification-service, and nothing blocks -- which is what lets these be
 * called from inside the eligibility transaction without putting a network hop
 * on the critical path of a cohort-sized computation.
 *
 * Why the outbox rather than the REST client auth-service uses: a drive blast is
 * fan-out. A synchronous POST that fails while notification-service restarts is
 * a blast that is never sent and never retried, and nobody finds out until a
 * student asks why they never heard about a drive. The outbox row commits with
 * the eligibility rows, so the notification is as durable as the eligibility
 * decision that caused it.
 *
 * NOTHING here throws. A notification is strictly downstream of the business
 * fact; resolution that succeeded must not be rolled back because an audience
 * query failed. Every method catches, logs, and returns.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriveNotificationService {

    /**
     * Who hears about drive outcomes. Deliberately the placement staff only:
     * HODs and faculty coordinators are not on this list, because they do not
     * act on an eligibility run and adding them triples the volume of a message
     * that is already borderline.
     */
    private static final Set<RoleType> STAFF_AUDIENCE = Set.of(
            RoleType.TNP_COORDINATOR,
            RoleType.TNP_COLLEGE_ADMIN,
            RoleType.TNP_SUPER_ADMIN);

    private static final DateTimeFormatter DEADLINE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(IstTime.ZONE);

    private final OutboxWriter outboxWriter;
    private final PostingEligibleStudentRepository eligibleStudentRepository;
    private final PostingTargetRepository targetRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final EntityManager entityManager;
    private final JobPostingRepository postingRepository;

    @Value("${yuktisetu.portal.student-url:http://localhost:3001}")
    private String studentPortalUrl;

    @Value("${yuktisetu.portal.admin-url:http://localhost:3000}")
    private String adminPortalUrl;

    /**
     * Tell every newly-eligible student that a drive is open.
     *
     * Called at the END of a resolution, not at publish time. At publish time
     * posting_eligible_students is still empty -- the resolution runs
     * asynchronously afterwards -- so a blast fired on PostingPublished would
     * address nobody. This is the reason the PostingPublished event has never
     * had a consumer: the useful moment is later than the event.
     *
     * The audience is exactly the set that can see the drive, taken from the
     * same table the visibility check reads, so a student can never be mailed
     * about a drive that would 404 for them.
     *
     * Marking notified is what makes it safe to call again: a re-evaluation
     * that admits twelve more students dispatches to those twelve, not to the
     * four hundred who were already told.
     *
     * MUST be called inside the resolution transaction. The outbox rows and the
     * notified flags then commit with the eligibility rows -- all three or none.
     */
    public void queueDrivePublishedBlast(Long postingId) {
        try {
            PostingNotice posting = noticeOf(postingId);
            if (!posting.notifyOnPublish()) {
                log.debug("Posting id={} has notifyOnPublish=false; skipping the student blast", posting.id());
                return;
            }

            List<NotifyTarget> audience = eligibleStudentRepository.findUnnotifiedRecipients(posting.id());
            if (audience.isEmpty()) {
                log.debug("No un-notified eligible students for postingId={}; nothing to blast", posting.id());
                return;
            }

            sendToStudents(posting, audience, Dispatches::drivePublished);

        } catch (Exception e) {
            // Deliberately swallowed. The eligibility resolution that just
            // succeeded is the valuable thing; losing the blast is recoverable by
            // re-running resolution, whereas rolling the resolution back is not.
            log.error("Could not queue the drive blast for postingId={}: {}",
                    postingId, e.getMessage(), e);
        }
    }

    /** Builds one student-facing dispatch; both blasts share the loop around it. */
    @FunctionalInterface
    private interface DispatchBuilder {
        NotificationDispatchEvent build(String companyName, String postingTitle, String deadline,
                                        String actionUrl, List<NotificationDispatchEvent.Recipient> recipients);
    }

    /** Chunks the audience into dispatch messages, writes them, and marks them notified. */
    private void sendToStudents(PostingNotice posting, List<NotifyTarget> audience, DispatchBuilder builder) {
        String companyName = posting.companyName();
        String title = posting.title();
        String deadline = formatDeadline(posting.applicationDeadline());
        String actionUrl = studentPortalUrl + "/drives/" + posting.id();

        List<Long> notified = new ArrayList<>(audience.size());
        int messages = 0;

        for (List<NotifyTarget> batch : Dispatches.chunk(audience, Dispatches.RECIPIENT_CHUNK)) {
            List<NotificationDispatchEvent.Recipient> recipients = new ArrayList<>(batch.size());
            for (NotifyTarget t : batch) {
                if (!t.isAddressable()) {
                    log.warn("Eligible student userId={} has no email address; not notified", t.userId());
                    continue;
                }
                recipients.add(new NotificationDispatchEvent.Recipient(
                        t.userId(), t.email(), t.displayName(),
                        Map.of("studentName", t.displayName())));
                notified.add(t.userId());
            }
            if (recipients.isEmpty()) {
                continue;
            }

            outboxWriter.write(
                    DriveTopics.NOTIFICATION_DISPATCH,
                    "NotificationDispatch",
                    "JobPosting",
                    posting.id(),
                    String.valueOf(posting.id()),
                    posting.publishedBy(),
                    builder.build(companyName, title, deadline, actionUrl, recipients));
            messages++;
        }

        if (!notified.isEmpty()) {
            eligibleStudentRepository.markNotified(posting.id(), notified);
        }

        log.info("Queued student dispatch for postingId={}: {} students in {} message(s)",
                posting.id(), notified.size(), messages);
    }

    /**
     * Tell EVERY currently-eligible student that a live drive was edited and
     * re-published -- including those already told about the original, and
     * regardless of notifyOnPublish: a change to a drive they may have applied to
     * is something they need to hear.
     *
     * Clears the notified flag first so the shared "unnotified" audience query
     * returns everyone eligible under the new criteria. Disqualified applicants
     * are not in the eligible set any more, so they get the disqualification
     * notice instead and never both.
     *
     * Same placement rules as the original blast: inside the resolution
     * transaction, and LAST, because markNotified clears the persistence context.
     */
    public void queueDriveUpdatedBlast(Long postingId) {
        try {
            PostingNotice posting = noticeOf(postingId);
            entityManager.createNativeQuery("""
                            UPDATE posting_eligible_students SET notified = false
                             WHERE posting_id = :postingId AND is_deleted = false
                            """)
                    .setParameter("postingId", posting.id())
                    .executeUpdate();

            List<NotifyTarget> audience = eligibleStudentRepository.findUnnotifiedRecipients(posting.id());
            if (audience.isEmpty()) {
                log.info("Re-publish of postingId={}: nobody is eligible, nothing to announce", posting.id());
                return;
            }
            sendToStudents(posting, audience, Dispatches::driveUpdated);
        } catch (Exception e) {
            log.error("Could not queue the drive-updated blast for postingId={}: {}",
                    postingId, e.getMessage(), e);
        }
    }

    /**
     * One disqualification notice per student, batched into as few dispatch
     * messages as possible. Same reason text for everyone, which is the case for a
     * bulk disqualification (criteria changed under them).
     */
    public void queueDisqualificationBatch(Long postingId, List<NotifyTarget> students,
                                           String reason, Long actorUserId) {
        try {
            PostingNotice posting = noticeOf(postingId);
            String companyName = posting.companyName();
            String title = posting.title();
            String actionUrl = studentPortalUrl + "/drives/" + posting.id();

            for (List<NotifyTarget> batch : Dispatches.chunk(students, Dispatches.RECIPIENT_CHUNK)) {
                List<NotificationDispatchEvent.Recipient> recipients = new ArrayList<>(batch.size());
                for (NotifyTarget t : batch) {
                    if (!t.isAddressable()) {
                        log.warn("Disqualified student userId={} on postingId={} has no email address; not notified",
                                t.userId(), posting.id());
                        continue;
                    }
                    recipients.add(new NotificationDispatchEvent.Recipient(
                            t.userId(), t.email(), t.displayName(),
                            Map.of("studentName", t.displayName())));
                }
                if (recipients.isEmpty()) {
                    continue;
                }
                outboxWriter.write(
                        DriveTopics.NOTIFICATION_DISPATCH,
                        "NotificationDispatch",
                        "JobPosting",
                        posting.id(),
                        String.valueOf(posting.id()),
                        actorUserId,
                        Dispatches.applicationDisqualified(companyName, title, reason, actionUrl, recipients));
            }
            log.info("Queued bulk disqualification notices: postingId={} students={}",
                    posting.id(), students.size());
        } catch (Exception e) {
            log.error("Could not queue bulk disqualification notices for postingId={}: {}",
                    postingId, e.getMessage(), e);
        }
    }

    /**
     * Tell the placement staff a resolution finished.
     *
     * IN_APP only. A coordinator publishing an afternoon's worth of drives should
     * find a handful of lines in the bell, not a mailbox full of them -- this is
     * informational, and they are already looking at the portal when it matters.
     */
    public void queueEligibilityRunComplete(Long postingId, int evaluated, int eligible) {
        try {
            PostingNotice posting = noticeOf(postingId);
            List<NotificationDispatchEvent.Recipient> staff = staffRecipients(posting.id());
            if (staff.isEmpty()) {
                return;
            }
            outboxWriter.write(
                    DriveTopics.NOTIFICATION_DISPATCH,
                    "NotificationDispatch",
                    "JobPosting",
                    posting.id(),
                    String.valueOf(posting.id()),
                    posting.publishedBy(),
                    Dispatches.eligibilityRunComplete(
                            posting.companyName(),
                            posting.title(),
                            evaluated,
                            eligible,
                            adminPortalUrl + "/drives/" + posting.id(),
                            staff));

            log.info("Queued eligibility-complete notice for postingId={} to {} staff member(s)",
                    posting.id(), staff.size());

        } catch (Exception e) {
            log.error("Could not queue the eligibility-complete notice for postingId={}: {}",
                    postingId, e.getMessage(), e);
        }
    }

    /**
     * Tell the placement staff a resolution FAILED, by email as well as in-app.
     *
     * This is the one staff message that earns an email, because a failed run is
     * invisible from the outside: the drive still reads PUBLISHED, no student
     * becomes eligible, no blast goes out, and the applicant pool simply stays
     * empty. Without this the first person to notice is a student asking why
     * they cannot see a drive their friends are talking about.
     *
     * Called from markFailed, which runs in its own REQUIRES_NEW transaction
     * after the main one rolled back -- so the outbox row survives even though
     * everything the failed run wrote did not.
     */
    public void queueEligibilityRunFailed(Long postingId, String reason) {
        try {
            PostingNotice posting = noticeOf(postingId);
            List<NotificationDispatchEvent.Recipient> staff = staffRecipients(posting.id());
            if (staff.isEmpty()) {
                log.warn("Eligibility run failed for postingId={} and there is NO staff audience to tell",
                        posting.id());
                return;
            }
            outboxWriter.write(
                    DriveTopics.NOTIFICATION_DISPATCH,
                    "NotificationDispatch",
                    "JobPosting",
                    posting.id(),
                    String.valueOf(posting.id()),
                    posting.publishedBy(),
                    Dispatches.eligibilityRunFailed(
                            posting.companyName(),
                            posting.title(),
                            reason,
                            adminPortalUrl + "/drives/" + posting.id(),
                            staff));

            log.info("Queued eligibility-FAILED alert for postingId={} to {} staff member(s)",
                    posting.id(), staff.size());

        } catch (Exception e) {
            log.error("Could not queue the eligibility-failed alert for postingId={}: {}",
                    postingId, e.getMessage(), e);
        }
    }

    /**
     * Tell one student the TnP removed them from a pool, with the reason.
     *
     * Not optional and not in-app only: being dropped from a drive is the single
     * most disputable thing that happens to a student in this system, and the
     * mail is the record that they were told and what they were told.
     */
    public void queueDisqualification(Long postingId, NotifyTarget student, String reason, Long actorUserId) {
        try {
            PostingNotice posting = noticeOf(postingId);
            if (student == null || !student.isAddressable()) {
                log.warn("Disqualified student on postingId={} has no email address; not notified",
                        posting.id());
                return;
            }
            outboxWriter.write(
                    DriveTopics.NOTIFICATION_DISPATCH,
                    "NotificationDispatch",
                    "JobPosting",
                    posting.id(),
                    String.valueOf(posting.id()),
                    actorUserId,
                    Dispatches.applicationDisqualified(
                            posting.companyName(),
                            posting.title(),
                            reason,
                            studentPortalUrl + "/drives/" + posting.id(),
                            List.of(new NotificationDispatchEvent.Recipient(
                                    student.userId(), student.email(), student.displayName(),
                                    Map.of("studentName", student.displayName())))));

            log.info("Queued disqualification notice: postingId={} studentUserId={}",
                    posting.id(), student.userId());

        } catch (Exception e) {
            log.error("Could not queue the disqualification notice for postingId={} studentUserId={}: {}",
                    postingId, student == null ? null : student.userId(), e.getMessage(), e);
        }
    }

    /**
     * The staff audience for a posting: everyone placement-side in the colleges
     * it targets, plus the trust-wide admins.
     *
     * Two queries, both batched, neither inside a loop.
     */
    private List<NotificationDispatchEvent.Recipient> staffRecipients(Long postingId) {
        List<Long> collegeIds = targetRepository.findByPosting_IdAndIsDeletedFalse(postingId).stream()
                .map(PostingTarget::getCollegeId)
                .distinct()
                .toList();
        if (collegeIds.isEmpty()) {
            return List.of();
        }

        List<NotifyTarget> staff = roleAssignmentRepository.findStaffAudience(STAFF_AUDIENCE, collegeIds);
        List<NotificationDispatchEvent.Recipient> recipients = new ArrayList<>(staff.size());
        for (NotifyTarget t : staff) {
            if (!t.isAddressable()) {
                continue;
            }
            recipients.add(new NotificationDispatchEvent.Recipient(
                    t.userId(), t.email(), t.displayName(), Map.of("staffName", t.displayName())));
        }
        return recipients;
    }

    /**
     * The drive's facts, read fresh by id as plain values.
     *
     * Never take a JobPosting entity here. Reading posting.getCompany().getName() off
     * a managed entity is a lazy load, and it throws once the entity is detached --
     * which every @Modifying(clearAutomatically = true) query in the resolution
     * transaction does -- so the notification was silently lost.
     */
    private PostingNotice noticeOf(Long postingId) {
        return postingRepository.findNoticeById(postingId)
                .orElseThrow(() -> new IllegalStateException("Posting " + postingId + " not found for notification"));
    }

    private String formatDeadline(Instant deadline) {
        return deadline == null ? "not set" : DEADLINE_FORMAT.format(deadline);
    }
}

package org.yuktisetu.adminservice.rounds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yuktisetu.adminservice.service.OutboxWriter;
import org.yuktisetu.core.time.IstTime;
import org.yuktisetu.drive.db.DriveRound;
import org.yuktisetu.drive.model.PostingNotice;
import org.yuktisetu.drive.model.RoundOutcome;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.identity.model.NotifyTarget;
import org.yuktisetu.identity.repository.UserRepository;
import org.yuktisetu.messaging.events.Dispatches;
import org.yuktisetu.messaging.events.DriveTopics;
import org.yuktisetu.messaging.events.NotificationDispatchEvent;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The rounds' notifications. Outbox rows only, written inside the transaction
 * that changed the results, so a published round and its emails commit or roll
 * back together -- and a Kafka or notification-service outage delays the emails
 * instead of losing them.
 *
 * Nothing here throws: the results are the record; a notice that could not be
 * assembled is logged and must not undo a published round.
 *
 * Every audience is ONE batched recipient list per outcome, chunked at
 * Dispatches.RECIPIENT_CHUNK, never one message per student.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoundNotifier {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a").withZone(IstTime.ZONE);

    private final OutboxWriter outboxWriter;
    private final UserRepository userRepository;
    private final JobPostingRepository postingRepository;

    @Value("${yuktisetu.portal.student-url:http://localhost:3001}")
    private String studentPortalUrl;

    @Value("${yuktisetu.portal.admin-url:http://localhost:3000}")
    private String adminPortalUrl;

    public static String when(Instant at) {
        return at == null ? null : WHEN.format(at);
    }

    /** "Round 2: Technical interview" */
    public static String roundName(DriveRound round) {
        return "Round " + round.getSeq() + ": " + round.getName();
    }

    /** The sentence a student reads about their result. */
    public static String outcomeText(DriveRound round, RoundOutcome outcome) {
        String name = roundName(round);
        return switch (outcome) {
            case ADVANCED -> "You have cleared " + name + ".";
            case ABSENT -> "You were marked absent for " + name + ", so you are not continuing in this drive.";
            case NOT_ADVANCED -> "You have not cleared " + name + ", so you are not continuing in this drive.";
        };
    }

    /** What an advancing student is told about the next round. */
    public static String nextStep(DriveRound next) {
        if (next == null) {
            return "You will be told about the next step as soon as it is decided.";
        }
        StringBuilder s = new StringBuilder("Next: ").append(roundName(next));
        if (next.getScheduledAt() != null) {
            s.append(", on ").append(when(next.getScheduledAt()));
        }
        if (next.getVenue() != null && !next.getVenue().isBlank()) {
            s.append(", at ").append(next.getVenue());
        }
        s.append('.');
        if (next.getScheduledAt() == null) {
            s.append(" You will be told the date and venue as soon as they are set.");
        }
        if (next.getInstructions() != null && !next.getInstructions().isBlank()) {
            s.append(' ').append(next.getInstructions().trim());
        }
        return s.toString();
    }

    public void roundResult(Long postingId, DriveRound round, RoundOutcome outcome,
                            Collection<Long> studentUserIds, String nextStep) {
        send("round result " + outcome, postingId, studentUserIds, (p) ->
                recipients -> Dispatches.roundResult(p.companyName(), p.title(), roundName(round),
                        outcomeText(round, outcome), outcome.advances(), nextStep, studentUrl(p), recipients));
    }

    /** The drive ended while these students were still in it. */
    public void driveEndedInProcess(Long postingId, Collection<Long> studentUserIds) {
        send("drive ended in process", postingId, studentUserIds, (p) ->
                recipients -> Dispatches.roundResult(p.companyName(), p.title(), "Drive ended",
                        "The TnP Cell has closed this drive before the remaining rounds, so it has ended "
                                + "without a selection for you.",
                        false, "", studentUrl(p), recipients));
    }

    public void offered(Long postingId, Collection<Long> studentUserIds, String opportunityLabel) {
        send("offered", postingId, studentUserIds, (p) ->
                recipients -> Dispatches.driveOffered(p.companyName(), p.title(), opportunityLabel,
                        studentUrl(p), recipients));
    }

    public void scheduled(Long postingId, DriveRound round, Collection<Long> studentUserIds, boolean changed) {
        send("round scheduled", postingId, studentUserIds, (p) ->
                recipients -> Dispatches.roundScheduled(p.companyName(), p.title(), roundName(round),
                        when(round.getScheduledAt()), round.getVenue(), round.getInstructions(), changed,
                        studentUrl(p), recipients));
    }

    public void corrected(Long postingId, DriveRound round, Long studentUserId, RoundOutcome from,
                          RoundOutcome to, String reason, String nextStep) {
        send("result corrected", postingId, List.of(studentUserId), (p) ->
                recipients -> Dispatches.roundResultCorrected(p.companyName(), p.title(), roundName(round),
                        from.label(), to.label(), reason, nextStep, studentUrl(p), recipients));
    }

    /**
     * The department report. recipients already carry their own department's
     * rows in their variables; this only batches and queues them.
     */
    public void departmentReport(Long postingId, List<NotificationDispatchEvent.Recipient> recipients) {
        try {
            PostingNotice p = notice(postingId);
            String url = adminPortalUrl + "/drives/" + postingId + "/rounds";
            for (List<NotificationDispatchEvent.Recipient> chunk : Dispatches.chunk(recipients, Dispatches.RECIPIENT_CHUNK)) {
                write(postingId, Dispatches.driveDepartmentReport(p.companyName(), p.title(), url, List.copyOf(chunk)));
            }
            log.info("Queued department report: postingId={} coordinators={}", postingId, recipients.size());
        } catch (Exception e) {
            log.error("Could not queue the department report for postingId={}: {}", postingId, e.getMessage(), e);
        }
    }

    /**
     * Every student whose application the cancel retired. Links to the "applied"
     * tab: a cancelled drive's own page is no longer shown to students.
     */
    public void driveCancelled(Long postingId, String reason, Collection<Long> studentIds) {
        String url = studentPortalUrl + "/drives?tab=applied";
        send("drive-cancelled", postingId, studentIds,
                p -> recipients -> Dispatches.driveCancelled(p.companyName(), p.title(), reason, url, recipients));
    }

    // ---------------------------------------------------------------- helpers

    private void send(String kind, Long postingId, Collection<Long> userIds,
                      Function<PostingNotice, Function<List<NotificationDispatchEvent.Recipient>, NotificationDispatchEvent>> build) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        try {
            PostingNotice p = notice(postingId);
            List<NotificationDispatchEvent.Recipient> all = new ArrayList<>();
            for (NotifyTarget t : userRepository.findNotifyTargets(userIds)) {
                if (t.isAddressable()) {
                    all.add(new NotificationDispatchEvent.Recipient(
                            t.userId(), t.email(), t.displayName(), Map.of("studentName", t.displayName())));
                }
            }
            if (all.size() < userIds.size()) {
                log.warn("{} notice for postingId={}: {} of {} students have no address", kind, postingId,
                        userIds.size() - all.size(), userIds.size());
            }
            Function<List<NotificationDispatchEvent.Recipient>, NotificationDispatchEvent> builder = build.apply(p);
            for (List<NotificationDispatchEvent.Recipient> chunk : Dispatches.chunk(all, Dispatches.RECIPIENT_CHUNK)) {
                write(postingId, builder.apply(List.copyOf(chunk)));
            }
            log.info("Queued {} notice: postingId={} students={}", kind, postingId, all.size());
        } catch (Exception e) {
            log.error("Could not queue the {} notice for postingId={}: {}", kind, postingId, e.getMessage(), e);
        }
    }

    private void write(Long postingId, NotificationDispatchEvent event) {
        outboxWriter.write(DriveTopics.NOTIFICATION_DISPATCH, "NotificationDispatch", "JobPosting",
                postingId, String.valueOf(postingId), null, event);
    }

    private String studentUrl(PostingNotice p) {
        return studentPortalUrl + "/drives/" + p.id();
    }

    /** Plain values read fresh by id: never a JobPosting whose lazy company may be detached. */
    private PostingNotice notice(Long postingId) {
        return postingRepository.findNoticeById(postingId)
                .orElseThrow(() -> new IllegalStateException("Posting " + postingId + " not found for notification"));
    }
}

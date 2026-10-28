package org.yuktisetu.adminservice.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yuktisetu.identity.model.NotifyTarget;

import java.time.Instant;
import java.util.List;

/**
 * After a live drive's eligibility criteria are changed and re-resolved, removes
 * the applicants who no longer qualify.
 *
 * Runs inside the resolution transaction, after the new eligible set is written:
 * "no longer qualifies" means "has a live application but is not in the eligible
 * set". The audit history is written FIRST (it reads from_status off the live
 * row), then the status update, then the student is told why.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationRequalificationService {

    static final String REASON =
            "The eligibility criteria for this drive were updated and your profile no longer meets them.";

    private static final String NOT_ELIGIBLE = """
            a.posting_id = :postingId
              AND a.is_deleted = false
              AND a.status IN ('APPLIED','IN_REVIEW','SHORTLISTED')
              AND NOT EXISTS (SELECT 1 FROM posting_eligible_students e
                               WHERE e.posting_id = a.posting_id
                                 AND e.student_user_id = a.student_user_id
                                 AND e.is_deleted = false)
            """;

    private final EntityManager entityManager;
    private final DriveNotificationService driveNotificationService;

    /** @return how many applications were disqualified */
    @SuppressWarnings("unchecked")
    public int disqualifyNoLongerEligible(Long postingId, Long actorUserId) {
        Instant now = Instant.now();

        List<Number> studentIds = entityManager.createNativeQuery(
                        "SELECT a.student_user_id FROM job_applications a WHERE " + NOT_ELIGIBLE)
                .setParameter("postingId", postingId)
                .getResultList();
        if (studentIds.isEmpty()) {
            return 0;
        }

        int audited = entityManager.createNativeQuery("""
                        INSERT INTO application_status_history (
                                application_id, from_status, to_status, changed_at, changed_by,
                                actor_kind, reason,
                                created_at, created_by, updated_at, updated_by, is_deleted)
                        SELECT a.id, a.status, 'DISQUALIFIED', :now, :actor,
                               'SYSTEM', :reason,
                               :now, :actor, :now, :actor, false
                          FROM job_applications a WHERE\s""" + NOT_ELIGIBLE)
                .setParameter("now", now)
                .setParameter("actor", actorUserId)
                .setParameter("reason", REASON)
                .setParameter("postingId", postingId)
                .executeUpdate();

        int updated = entityManager.createNativeQuery("""
                        UPDATE job_applications a
                           SET status = 'DISQUALIFIED', disqualification_reason = :reason,
                               disqualified_at = :now, disqualified_by = :actor,
                               updated_at = :now, updated_by = :actor
                         WHERE\s""" + NOT_ELIGIBLE)
                .setParameter("now", now)
                .setParameter("actor", actorUserId)
                .setParameter("reason", REASON)
                .setParameter("postingId", postingId)
                .executeUpdate();

        if (audited != updated) {
            throw new IllegalStateException("Audit/disqualify mismatch on posting " + postingId
                    + ": audited=" + audited + " disqualified=" + updated);
        }

        List<Long> ids = studentIds.stream().map(Number::longValue).toList();
        List<NotifyTarget> targets = entityManager.createQuery("""
                        select new org.yuktisetu.identity.model.NotifyTarget(u.id, u.email, u.firstName, u.lastName)
                          from User u where u.id in :ids and u.isDeleted = false
                        """, NotifyTarget.class)
                .setParameter("ids", ids)
                .getResultList();
        driveNotificationService.queueDisqualificationBatch(postingId, targets, REASON, actorUserId);

        log.warn("Re-publish of posting {} disqualified {} applicant(s) who no longer meet the criteria",
                postingId, updated);
        return updated;
    }
}

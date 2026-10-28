package org.yuktisetu.adminservice.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Stops the work a drive still has in flight when it is cancelled.
 *
 * The applications are retired by JobPostingService itself (with their audit
 * history). This covers the rest:
 *
 *  - an eligibility resolution still RUNNING would go on to write an eligible set
 *    for a dead drive;
 *  - an open scoring gate (COLLECTING / AWAITING_TNP_REVIEW) would sit waiting for
 *    scrapes and TnP reviews that no longer matter;
 *  - notifications still queued in the outbox (a drive-open blast, a staff notice)
 *    would be delivered after the drive was cancelled, telling students about a
 *    drive that no longer exists.
 *
 * Must run inside the cancel transaction, so either the drive and everything it
 * had running stop together, or none of it does.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostingCancellationCascade {

    private final EntityManager entityManager;

    public record Result(int runsStopped, int gatesCancelled, int notificationsDropped) {}

    public Result stop(Long postingId, Instant now, Long actorUserId) {
        int runs = entityManager.createNativeQuery("""
                        UPDATE eligibility_runs
                           SET status = 'FAILED', finished_at = :now, updated_at = :now, updated_by = :actor,
                               error = 'Stopped: the drive was cancelled while this resolution was running'
                         WHERE posting_id = :postingId AND status = 'RUNNING'
                        """)
                .setParameter("now", now)
                .setParameter("actor", actorUserId)
                .setParameter("postingId", postingId)
                .executeUpdate();

        int gates = entityManager.createNativeQuery("""
                        UPDATE drive_scoring_gates
                           SET state = 'CANCELLED', updated_at = :now
                         WHERE posting_id = :postingId AND state IN ('COLLECTING', 'AWAITING_TNP_REVIEW')
                        """)
                .setParameter("now", now)
                .setParameter("postingId", postingId)
                .executeUpdate();

        // FAILED, not deleted: the row stays as the record of what was dropped, and
        // the relay only ever picks up PENDING rows.
        int outbox = entityManager.createNativeQuery("""
                        UPDATE outbox_events
                           SET status = 'FAILED', updated_at = :now,
                               last_error = 'Dropped: the drive was cancelled before this was delivered'
                         WHERE aggregate_type = 'JobPosting' AND aggregate_id = :postingKey AND status = 'PENDING'
                        """)
                .setParameter("now", now)
                .setParameter("postingKey", String.valueOf(postingId))
                .executeUpdate();

        return new Result(runs, gates, outbox);
    }
}

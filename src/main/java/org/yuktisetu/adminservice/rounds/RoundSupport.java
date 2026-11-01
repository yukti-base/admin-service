package org.yuktisetu.adminservice.rounds;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.policy.PlacementDrivePolicy;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.DriveRound;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.PostingTarget;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.RoundOutcome;
import org.yuktisetu.drive.model.RoundStatus;
import org.yuktisetu.drive.repository.DriveRoundRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PostingTargetRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What every round service needs: the drive, its targets and the actor's
 * authority over it, the drive's current round, and the lock order.
 *
 * THE LOCK ORDER, for every path that changes rounds or their results:
 *   1. the posting (shared for a mark, exclusive for anything that decides for
 *      the whole drive -- publish, correct, edit, end; drive-service's reopen and
 *      the cancellation cascade take it exclusively too);
 *   2. then the round row, exclusively.
 * Always in that order, so no two of them can deadlock, and a mark can never
 * land on a round between a publish reading its marks and committing.
 */
@Component
@RequiredArgsConstructor
public class RoundSupport {

    private final JobPostingRepository postingRepository;
    private final PostingTargetRepository targetRepository;
    private final DriveRoundRepository roundRepository;
    private final PlacementDrivePolicy policy;
    private final NamedParameterJdbcTemplate jdbc;

    public enum Access { VIEW, MARK, MANAGE }

    // ------------------------------------------------------------- the drive

    public JobPosting posting(Long postingId) {
        return postingRepository.findByIdAndIsDeletedFalse(postingId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", postingId));
    }

    /** Shared lock: many marks at once, but none while a publish/correct/edit holds it. */
    public JobPosting postingShared(Long postingId) {
        return postingRepository.findForApply(postingId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", postingId));
    }

    public JobPosting postingExclusive(Long postingId) {
        return postingRepository.findForSealing(postingId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", postingId));
    }

    public List<Long> targetColleges(Long postingId) {
        return targetRepository.findByPosting_IdAndIsDeletedFalse(postingId).stream()
                .map(PostingTarget::getCollegeId).distinct().toList();
    }

    public void authorise(UserPrincipal actor, Long postingId, Access access) {
        List<Long> colleges = targetColleges(postingId);
        boolean allowed = switch (access) {
            case VIEW -> policy.canViewRounds(actor, colleges);
            case MARK -> policy.canMarkRounds(actor, colleges);
            case MANAGE -> policy.canManageRounds(actor, colleges);
        };
        if (!allowed) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
    }

    public boolean allowed(UserPrincipal actor, List<Long> colleges, Access access) {
        return switch (access) {
            case VIEW -> policy.canViewRounds(actor, colleges);
            case MARK -> policy.canMarkRounds(actor, colleges);
            case MANAGE -> policy.canManageRounds(actor, colleges);
        };
    }

    /**
     * Rounds run between the shortlist and the end of the drive: the shortlist
     * must be out, and the drive still sealed or in progress -- not reopened,
     * cancelled or ended.
     */
    public void requireRoundsRunning(JobPosting posting) {
        if (posting.getStatus() == PostingStatus.CANCELLED) {
            throw new AdminExceptions.RoundStateException("DRIVE_CANCELLED", "This drive was cancelled.");
        }
        if (posting.getStatus() == PostingStatus.COMPLETED) {
            throw new AdminExceptions.RoundStateException("DRIVE_ENDED",
                    "This drive has ended; its results can no longer be changed.");
        }
        if (posting.getShortlistPublishedAt() == null
                || (posting.getStatus() != PostingStatus.APPLICATIONS_CLOSED
                    && posting.getStatus() != PostingStatus.IN_PROGRESS)) {
            throw new AdminExceptions.RoundStateException("SHORTLIST_NOT_PUBLISHED",
                    "Rounds start once the drive's shortlist is published.");
        }
    }

    // ------------------------------------------------------------- the rounds

    /** The round that can be marked now: the lowest one still PLANNED. */
    public static DriveRound current(List<DriveRound> ordered) {
        for (DriveRound r : ordered) {
            if (r.getStatus() == RoundStatus.PLANNED) {
                return r;
            }
        }
        return null;
    }

    public DriveRound lockRound(Long postingId, Long roundId) {
        DriveRound round = roundRepository.lockById(roundId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("DriveRound", roundId));
        if (!round.getPostingId().equals(postingId)) {
            throw new AdminExceptions.NotFoundException("DriveRound", roundId);
        }
        return round;
    }

    /** The round without a lock, for reads. */
    public DriveRound round(Long postingId, Long roundId) {
        return roundRepository.findById(roundId)
                .filter(r -> r.getPostingId().equals(postingId))
                .orElseThrow(() -> new AdminExceptions.NotFoundException("DriveRound", roundId));
    }

    public boolean isCurrent(DriveRound round) {
        if (round.getStatus() != RoundStatus.PLANNED) {
            return false;
        }
        DriveRound current = current(roundRepository.findByPostingIdOrderBySeqAsc(round.getPostingId()));
        return current != null && current.getId().equals(round.getId());
    }

    /** Draft or published counts for a round, in one grouped query. */
    public Map<RoundOutcome, Integer> outcomeCounts(Long roundId) {
        Map<RoundOutcome, Integer> out = new HashMap<>();
        jdbc.query("SELECT outcome, count(*) FROM round_results WHERE round_id = :id GROUP BY outcome",
                new MapSqlParameterSource("id", roundId),
                rs -> { out.put(RoundOutcome.valueOf(rs.getString(1)), rs.getInt(2)); });
        return out;
    }

    /** The current round's candidates: the drive's applications still SHORTLISTED. */
    public int candidateCount(Long postingId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM job_applications
                 WHERE posting_id = :p AND status = 'SHORTLISTED' AND is_deleted = false
                """, new MapSqlParameterSource("p", postingId), Integer.class);
        return n == null ? 0 : n;
    }

    /** Counts for a round as the TnP sees them: drafts against the live candidate list. */
    public RoundDtos.Counts counts(DriveRound round, boolean current) {
        Map<RoundOutcome, Integer> c = outcomeCounts(round.getId());
        int advanced = c.getOrDefault(RoundOutcome.ADVANCED, 0);
        int notAdvanced = c.getOrDefault(RoundOutcome.NOT_ADVANCED, 0);
        int absent = c.getOrDefault(RoundOutcome.ABSENT, 0);
        int unmarked = 0;
        if (current) {
            // Marks are only kept for candidates, but a candidate can leave
            // (disqualified) after being marked -- count against the live list.
            Integer marked = jdbc.queryForObject("""
                    SELECT count(*) FROM round_results rr
                      JOIN job_applications a ON a.id = rr.application_id
                     WHERE rr.round_id = :r AND a.status = 'SHORTLISTED' AND a.is_deleted = false
                    """, new MapSqlParameterSource("r", round.getId()), Integer.class);
            unmarked = Math.max(0, candidateCount(round.getPostingId()) - (marked == null ? 0 : marked));
        }
        return new RoundDtos.Counts(advanced, notAdvanced, absent, unmarked);
    }

    // ------------------------------------------------------------------ people

    /** userId -> "First Last", one query. */
    public Map<Long, String> names(Collection<Long> userIds) {
        Map<Long, String> out = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT id, first_name, last_name FROM users WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", userIds),
                rs -> { out.put(rs.getLong(1), name(rs.getString(2), rs.getString(3))); });
        return out;
    }

    public static String name(String first, String last) {
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }

    /** The Postgres driver hands back Instant, Timestamp or OffsetDateTime depending on the path. */
    public static Instant instant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Instant i) {
            return i;
        }
        if (raw instanceof Timestamp t) {
            return t.toInstant();
        }
        if (raw instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        throw new IllegalArgumentException("Not a timestamp: " + raw.getClass());
    }
}

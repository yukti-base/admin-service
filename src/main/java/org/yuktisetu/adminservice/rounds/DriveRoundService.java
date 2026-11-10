package org.yuktisetu.adminservice.rounds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.rounds.RoundDtos.Counts;
import org.yuktisetu.adminservice.rounds.RoundDtos.HistoryEntry;
import org.yuktisetu.adminservice.rounds.RoundDtos.RoundRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.RoundSheet;
import org.yuktisetu.adminservice.rounds.RoundDtos.RoundView;
import org.yuktisetu.adminservice.rounds.RoundDtos.RoundsOverview;
import org.yuktisetu.adminservice.rounds.RoundDtos.SheetRow;
import org.yuktisetu.adminservice.rounds.RoundSupport.Access;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.DriveRound;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.RoundResultHistory;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.OfferType;
import org.yuktisetu.drive.model.OfferTypes;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.RoundOutcome;
import org.yuktisetu.drive.model.RoundStatus;
import org.yuktisetu.drive.repository.DriveRoundRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.RoundResultHistoryRepository;
import org.yuktisetu.drive.repository.RoundResultRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Defining a drive's rounds, and reading them: the Rounds tab and each round's
 * sheet of candidates.
 *
 * Rounds can be defined from the draft onwards and added while the drive runs
 * (a company adding an extra interview is ordinary). What cannot change is
 * history: a published round is never edited or removed, and nothing is added
 * after the final round is published.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriveRoundService {

    private final RoundSupport support;
    private final DriveRoundRepository roundRepository;
    private final RoundResultRepository resultRepository;
    private final RoundResultHistoryRepository historyRepository;
    private final JobApplicationRepository applicationRepository;
    private final RoundNotifier notifier;
    private final NamedParameterJdbcTemplate jdbc;

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public RoundsOverview overview(UserPrincipal actor, Long postingId) {
        try {
            JobPosting posting = support.posting(postingId);
            List<Long> colleges = support.targetColleges(postingId);
            if (!support.allowed(actor, colleges, Access.VIEW)) {
                throw new AdminExceptions.InsufficientAuthorityException();
            }
            List<DriveRound> rounds = roundRepository.findByPostingIdOrderBySeqAsc(postingId);
            DriveRound current = RoundSupport.current(rounds);
            boolean running = posting.getShortlistPublishedAt() != null
                    && (posting.getStatus() == PostingStatus.APPLICATIONS_CLOSED
                        || posting.getStatus() == PostingStatus.IN_PROGRESS);
            Map<Long, String> publishers = support.names(rounds.stream()
                    .map(DriveRound::getPublishedBy).filter(Objects::nonNull).toList());

            List<RoundView> views = new ArrayList<>(rounds.size());
            for (int i = 0; i < rounds.size(); i++) {
                DriveRound r = rounds.get(i);
                boolean isCurrent = running && current != null && current.getId().equals(r.getId());
                views.add(view(r, isCurrent, i == rounds.size() - 1, publishers.get(r.getPublishedBy()),
                        support.counts(r, isCurrent)));
            }
            Map<String, Long> statusCounts = new java.util.HashMap<>();
            for (Object[] row : applicationRepository.countByStatusGrouped(postingId)) {
                statusCounts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
            }
            OfferType defaultType = posting.getJobType() == null ? OfferType.OTHER
                    : OfferTypes.forJobType(posting.getJobType());
            return new RoundsOverview(
                    postingId,
                    posting.getStatus().name(),
                    posting.getShortlistingMode().name(),
                    posting.getShortlistPublishedAt() != null,
                    posting.getStatus() == PostingStatus.COMPLETED,
                    posting.getJobType() == null ? null : posting.getJobType().name(),
                    defaultType,
                    Arrays.asList(OfferType.values()),
                    support.allowed(actor, colleges, Access.MANAGE),
                    support.allowed(actor, colleges, Access.MARK),
                    statusCounts.getOrDefault(ApplicationStatus.SHORTLISTED.name(), 0L).intValue(),
                    statusCounts.getOrDefault(ApplicationStatus.OFFERED.name(), 0L).intValue(),
                    running && current != null ? current.getId() : null,
                    views);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not load rounds for postingId={} actorId={}: {}", postingId, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load the drive's rounds. Please try again.");
        }
    }

    /**
     * One round's sheet. For the current round: every candidate (the drive's
     * SHORTLISTED applications) with their draft mark, if any. For a published
     * round: everyone who took it, with their result. A later planned round has
     * no candidates yet.
     *
     * knownVersion makes polling cheap: while other markers work, the portal asks
     * every few seconds, and an unchanged round answers without reading a row.
     */
    @Transactional(readOnly = true)
    public RoundSheet sheet(UserPrincipal actor, Long postingId, Long roundId, Long knownVersion) {
        try {
            JobPosting posting = support.posting(postingId);
            support.authorise(actor, postingId, Access.VIEW);
            DriveRound round = roundRepository.findById(roundId)
                    .filter(r -> r.getPostingId().equals(postingId))
                    .orElseThrow(() -> new AdminExceptions.NotFoundException("DriveRound", roundId));
            boolean running = posting.getShortlistPublishedAt() != null
                    && (posting.getStatus() == PostingStatus.APPLICATIONS_CLOSED
                        || posting.getStatus() == PostingStatus.IN_PROGRESS);
            boolean current = running && support.isCurrent(round);

            if (knownVersion != null && knownVersion == round.getMarksVersion()) {
                return new RoundSheet(roundId, round.getStatus().name(), current, round.getMarksVersion(),
                        true, null, List.of());
            }
            List<SheetRow> rows;
            if (round.getStatus() == RoundStatus.PUBLISHED) {
                rows = rows(postingId, roundId, false);
            } else if (current) {
                rows = rows(postingId, roundId, true);
            } else {
                rows = List.of();
            }
            return new RoundSheet(roundId, round.getStatus().name(), current, round.getMarksVersion(), false,
                    support.counts(round, current), rows);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not load round sheet postingId={} roundId={}: {}", postingId, roundId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load this round. Please try again.");
        }
    }

    /** Every change to one student's result in one round, oldest first. */
    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UserPrincipal actor, Long postingId, Long roundId, Long applicationId) {
        try {
            support.posting(postingId);
            support.authorise(actor, postingId, Access.VIEW);
            DriveRound round = roundRepository.findById(roundId)
                    .filter(r -> r.getPostingId().equals(postingId))
                    .orElseThrow(() -> new AdminExceptions.NotFoundException("DriveRound", roundId));
            List<RoundResultHistory> rows =
                    historyRepository.findByRoundIdAndApplicationIdOrderByChangedAtAscIdAsc(round.getId(), applicationId);
            Map<Long, String> who = support.names(rows.stream().map(RoundResultHistory::getChangedBy).toList());
            return rows.stream().map(h -> new HistoryEntry(
                    h.getFromOutcome() == null ? null : h.getFromOutcome().label(),
                    h.getToOutcome() == null ? null : h.getToOutcome().label(),
                    h.getKind().name(), h.getSource().name(), h.getReason(),
                    who.getOrDefault(h.getChangedBy(), "Unknown"), h.getChangedAt())).toList();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not load round history postingId={} roundId={} applicationId={}: {}",
                    postingId, roundId, applicationId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load this result's history.");
        }
    }

    // ----------------------------------------------------------------- define

    @Transactional
    public RoundView add(UserPrincipal actor, Long postingId, RoundRequest req) {
        try {
            JobPosting posting = support.postingExclusive(postingId);
            support.authorise(actor, postingId, Access.MANAGE);
            requireDefinable(posting);
            List<DriveRound> rounds = roundRepository.findByPostingIdOrderBySeqAsc(postingId);
            if (rounds.stream().anyMatch(DriveRound::isFinalRound)) {
                throw new AdminExceptions.RoundStateException("FINAL_ROUND_PUBLISHED",
                        "The final round of this drive is already published, so no round can be added after it.");
            }
            if (req.roundType() == null) {
                throw new AdminExceptions.InvalidRequestException("Choose the kind of round.");
            }
            if (rounds.size() >= 20) {
                throw new AdminExceptions.InvalidRequestException("A drive can have at most 20 rounds.");
            }
            DriveRound round = new DriveRound();
            round.setPostingId(postingId);
            round.setSeq(rounds.isEmpty() ? 1 : rounds.get(rounds.size() - 1).getSeq() + 1);
            round.setName(blank(req.name()) ? req.roundType().label() : req.name().trim());
            round.setRoundType(req.roundType());
            round.setScheduledAt(Boolean.TRUE.equals(req.clearSchedule()) ? null : req.scheduledAt());
            round.setVenue(trimToNull(req.venue()));
            round.setInstructions(trimToNull(req.instructions()));
            round.setCreatedBy(actor.userId());
            round.setUpdatedBy(actor.userId());
            round = roundRepository.saveAndFlush(round);

            // A round added while the drive runs can become the current round at
            // once (every earlier one is published). Its candidates were told
            // "you will be told the date and venue", so tell them now.
            boolean current = running(posting) && support.isCurrent(round);
            if (current && !Boolean.FALSE.equals(req.notifyCandidates()) && hasDetails(round)) {
                notifier.scheduled(postingId, round, candidates(postingId), false);
            }
            log.info("Round added: postingId={} roundId={} seq={} type={} actorId={}",
                    postingId, round.getId(), round.getSeq(), round.getRoundType(), actor.userId());
            return view(round, current, true, null, support.counts(round, current));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not add a round to postingId={}: {}", postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not add the round. Please try again.");
        }
    }

    /**
     * Edits a planned round. When it is the current round and its date, venue or
     * instructions changed, its candidates are told (unless the editor unticks
     * that) -- an interview moved by an hour that nobody hears about is a student
     * who misses it.
     */
    @Transactional
    public RoundView edit(UserPrincipal actor, Long postingId, Long roundId, RoundRequest req) {
        try {
            JobPosting posting = support.postingExclusive(postingId);
            support.authorise(actor, postingId, Access.MANAGE);
            requireDefinable(posting);
            DriveRound round = support.lockRound(postingId, roundId);
            if (round.getStatus() == RoundStatus.PUBLISHED) {
                throw new AdminExceptions.RoundStateException("ROUND_PUBLISHED",
                        "This round's results are published, so it can no longer be edited.");
            }
            if (req.expectedVersion() != null && req.expectedVersion() != round.getVersion()) {
                throw new AdminExceptions.RoundStateException("ROUND_EDITED",
                        "Someone else changed this round while you were editing it. Reload it and try again.");
            }
            boolean hadDetails = hasDetails(round);
            var before = java.util.Arrays.asList(round.getScheduledAt(), round.getVenue(), round.getInstructions());

            if (!blank(req.name())) {
                round.setName(req.name().trim());
            }
            if (req.roundType() != null) {
                round.setRoundType(req.roundType());
            }
            if (Boolean.TRUE.equals(req.clearSchedule())) {
                round.setScheduledAt(null);
            } else if (req.scheduledAt() != null) {
                round.setScheduledAt(req.scheduledAt());
            }
            if (req.venue() != null) {
                round.setVenue(trimToNull(req.venue()));
            }
            if (req.instructions() != null) {
                round.setInstructions(trimToNull(req.instructions()));
            }
            round.setUpdatedBy(actor.userId());
            round = roundRepository.saveAndFlush(round);

            var after = java.util.Arrays.asList(round.getScheduledAt(), round.getVenue(), round.getInstructions());
            boolean detailsChanged = !before.equals(after);
            boolean current = running(posting) && support.isCurrent(round);
            if (current && detailsChanged && !Boolean.FALSE.equals(req.notifyCandidates()) && hasDetails(round)) {
                notifier.scheduled(postingId, round, candidates(postingId), hadDetails);
            }
            List<DriveRound> all = roundRepository.findByPostingIdOrderBySeqAsc(postingId);
            boolean last = all.get(all.size() - 1).getId().equals(round.getId());
            log.info("Round edited: postingId={} roundId={} detailsChanged={} notified={} actorId={}",
                    postingId, roundId, detailsChanged, current && detailsChanged, actor.userId());
            return view(round, current, last, null, support.counts(round, current));
        } catch (ApiException e) {
            throw e;
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new AdminExceptions.RoundStateException("ROUND_EDITED",
                    "Someone else changed this round while you were editing it. Reload it and try again.");
        } catch (Exception e) {
            log.error("Could not edit roundId={} of postingId={}: {}", roundId, postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not save the round. Please try again.");
        }
    }

    /**
     * Removes a planned round nobody has been marked in. The rounds after it
     * move up one, so the sequence stays 1, 2, 3 without gaps.
     */
    @Transactional
    public void delete(UserPrincipal actor, Long postingId, Long roundId) {
        try {
            JobPosting posting = support.postingExclusive(postingId);
            support.authorise(actor, postingId, Access.MANAGE);
            requireDefinable(posting);
            DriveRound round = support.lockRound(postingId, roundId);
            if (round.getStatus() == RoundStatus.PUBLISHED) {
                throw new AdminExceptions.RoundStateException("ROUND_PUBLISHED",
                        "This round's results are published, so it cannot be removed.");
            }
            int marked = resultRepository.findByRoundId(roundId).size();
            if (marked > 0) {
                throw new AdminExceptions.RoundStateException("ROUND_HAS_MARKS",
                        marked + " student(s) are already marked in this round. Clear their marks before removing it.");
            }
            int seq = round.getSeq();
            // Its own history rows only exist with marks, which were refused above.
            roundRepository.delete(round);
            roundRepository.flush();
            // Two steps, because uk_drive_round_seq is checked row by row: shifting
            // 3->2 while 2 still exists would collide. Move them out of the way
            // first, then into place.
            MapSqlParameterSource p = new MapSqlParameterSource("p", postingId).addValue("seq", seq);
            jdbc.update("UPDATE drive_rounds SET seq = seq + 10000 WHERE posting_id = :p AND seq > :seq", p);
            jdbc.update("UPDATE drive_rounds SET seq = seq - 10001 WHERE posting_id = :p AND seq > 10000", p);
            log.info("Round removed: postingId={} roundId={} seq={} actorId={}", postingId, roundId, seq, actor.userId());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not remove roundId={} of postingId={}: {}", roundId, postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not remove the round. Please try again.");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void requireDefinable(JobPosting posting) {
        if (posting.getStatus() == PostingStatus.CANCELLED || posting.getStatus() == PostingStatus.COMPLETED) {
            throw new AdminExceptions.RoundStateException("DRIVE_CLOSED",
                    "This drive is " + posting.getStatus().name().toLowerCase() + ", so its rounds can no longer change.");
        }
    }

    private static boolean running(JobPosting posting) {
        return posting.getShortlistPublishedAt() != null
                && (posting.getStatus() == PostingStatus.APPLICATIONS_CLOSED
                    || posting.getStatus() == PostingStatus.IN_PROGRESS);
    }

    private List<Long> candidates(Long postingId) {
        return jdbc.queryForList("""
                SELECT student_user_id FROM job_applications
                 WHERE posting_id = :p AND status = 'SHORTLISTED' AND is_deleted = false
                """, new MapSqlParameterSource("p", postingId), Long.class);
    }

    private List<SheetRow> rows(Long postingId, Long roundId, boolean current) {
        // One query: candidate, profile PRN, college and department, mark and
        // marker. LATERAL picks one STUDENT assignment so a student with two
        // (a data problem) shows once instead of twice.
        String sql = """
                SELECT a.id, u.id, u.first_name, u.last_name, u.email, sp.prn,
                       ura.college_name, ura.dept_name,
                       rr.outcome, rr.version, rr.source, mu.first_name, mu.last_name, rr.marked_at, a.status,
                       (u.is_deleted = false AND u.status = 'ACTIVE' AND ura.has_role IS NOT NULL) AS account_active
                  FROM job_applications a
                  JOIN users u ON u.id = a.student_user_id
                  LEFT JOIN student_profiles sp ON sp.user_id = u.id
                  LEFT JOIN LATERAL (
                        SELECT c.name AS college_name, d.name AS dept_name, true AS has_role
                          FROM user_role_assignments x
                          LEFT JOIN colleges c ON c.id = x.college_id
                          LEFT JOIN departments d ON d.id = x.dept_id
                         WHERE x.user_id = u.id AND x.role = 'STUDENT' AND x.is_active = true
                         ORDER BY x.id LIMIT 1) ura ON true
                  %s JOIN round_results rr ON rr.round_id = :r AND rr.application_id = a.id
                  LEFT JOIN users mu ON mu.id = rr.marked_by
                 WHERE a.posting_id = :p AND a.is_deleted = false %s
                 ORDER BY lower(u.first_name), lower(u.last_name), a.id
                """.formatted(current ? "LEFT" : "", current ? "AND a.status = 'SHORTLISTED'" : "");
        return jdbc.query(sql, new MapSqlParameterSource("p", postingId).addValue("r", roundId), (rs, i) ->
                new SheetRow(
                        rs.getLong(1), rs.getLong(2), RoundSupport.name(rs.getString(3), rs.getString(4)),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9) == null ? null : RoundOutcome.valueOf(rs.getString(9)),
                        rs.getString(9) == null ? null : rs.getInt(10),
                        rs.getString(11),
                        rs.getString(12) == null ? null : RoundSupport.name(rs.getString(12), rs.getString(13)),
                        RoundSupport.instant(rs.getObject(14)),
                        rs.getString(15),
                        rs.getBoolean(16)));
    }

    static RoundView view(DriveRound r, boolean current, boolean last, String publishedByName, Counts counts) {
        return new RoundView(r.getId(), r.getSeq(), r.getName(), r.getRoundType(), r.getRoundType().label(),
                r.getScheduledAt(), r.getVenue(), r.getInstructions(), r.getStatus().name(), current,
                r.isFinalRound(), last, r.getPublishedAt(), publishedByName, r.getMarksVersion(), r.getVersion(),
                counts);
    }

    private static boolean hasDetails(DriveRound r) {
        return r.getScheduledAt() != null || !blank(r.getVenue()) || !blank(r.getInstructions());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return blank(s) ? null : s.trim();
    }
}

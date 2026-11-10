package org.yuktisetu.adminservice.rounds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.rounds.RoundDtos.CorrectionRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.PublishRoundRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.PublishRoundResult;
import org.yuktisetu.adminservice.rounds.RoundDtos.RoundView;
import org.yuktisetu.adminservice.rounds.RoundSupport.Access;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.DriveRound;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.RoundResult;
import org.yuktisetu.drive.model.ActorKind;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.OfferType;
import org.yuktisetu.drive.model.OfferTypes;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.RoundChangeKind;
import org.yuktisetu.drive.model.RoundMarkSource;
import org.yuktisetu.drive.model.RoundOutcome;
import org.yuktisetu.drive.model.RoundRules;
import org.yuktisetu.drive.model.RoundStatus;
import org.yuktisetu.drive.repository.DriveRoundRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.RoundResultRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Publishing a round, and correcting a published result.
 *
 * Both decide for the whole drive, so both take the posting's exclusive lock
 * and then the round's -- no mark, other publish or correction can interleave.
 *
 * Edge cases, and what happens (decided 2026-10-30):
 *  - Marks changed after the coordinator looked: refused (MARKS_CHANGED). They
 *    reload, see the new marks, and publish what they actually reviewed.
 *  - Candidates left unmarked: refused until confirmed (UNMARKED_CANDIDATES);
 *    confirmed, they are recorded as not advanced, and the record says it was a
 *    default, not a volunteer's mark.
 *  - Marks on students no longer candidates (disqualified after being marked):
 *    dropped, with a history row -- the round's results only ever cover the
 *    candidates who were in it.
 *  - The final round: must be confirmed as final (an advancing student is marked
 *    OFFERED and congratulated), and is refused if any advancing student cannot
 *    be recorded (no college, or a live internship on an internship drive).
 *  - Double click / two coordinators: the second finds the round PUBLISHED.
 *  - A correction: only by a coordinator or above, with a reason, while the next
 *    round is unpublished; it moves the application across the line in either
 *    direction, revokes or records the offer on the final round, and clears the
 *    student's draft marks in the next round if they are taken out of it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoundPublishingService {

    private final RoundSupport support;
    private final DriveRoundRepository roundRepository;
    private final RoundResultRepository resultRepository;
    private final JobApplicationRepository applicationRepository;
    private final JobPostingRepository postingRepository;
    private final OfferRecorder offers;
    private final RoundNotifier notifier;
    private final NamedParameterJdbcTemplate jdbc;

    // ---------------------------------------------------------------- publish

    @Transactional
    public PublishRoundResult publish(UserPrincipal actor, Long postingId, Long roundId, PublishRoundRequest req) {
        try {
            JobPosting posting = support.postingExclusive(postingId);
            support.authorise(actor, postingId, Access.MANAGE);
            support.requireRoundsRunning(posting);
            DriveRound round = support.lockRound(postingId, roundId);
            final int seq = round.getSeq();
            if (round.getStatus() == RoundStatus.PUBLISHED) {
                throw new AdminExceptions.RoundStateException("ROUND_PUBLISHED",
                        "This round's results were already published.");
            }
            List<DriveRound> rounds = roundRepository.findByPostingIdOrderBySeqAsc(postingId);
            DriveRound current = RoundSupport.current(rounds);
            if (current == null || !current.getId().equals(roundId)) {
                throw new AdminExceptions.RoundStateException("NOT_CURRENT_ROUND",
                        "Publish the earlier rounds first.");
            }
            if (req.marksVersion() != round.getMarksVersion()) {
                throw new AdminExceptions.RoundStateException("MARKS_CHANGED",
                        "Marks in this round changed since you reviewed them. Reload the round, check the "
                                + "marks and publish again.");
            }
            boolean last = rounds.get(rounds.size() - 1).getId().equals(roundId);
            boolean finalRound = Boolean.TRUE.equals(req.finalRound());
            if (last && !finalRound) {
                throw new AdminExceptions.RoundStateException("CONFIRM_FINAL",
                        "This is the drive's last round: students who advance will be marked offered. "
                                + "Confirm that it is the final round, or add the next round first.");
            }
            if (!last && finalRound) {
                throw new AdminExceptions.RoundStateException("NOT_LAST_ROUND",
                        "There are rounds after this one, so it cannot be published as the final round. "
                                + "Remove the later rounds first if the company has dropped them.");
            }
            OfferType offerType = req.offerType() != null ? req.offerType()
                    : posting.getJobType() == null ? OfferType.OTHER : OfferTypes.forJobType(posting.getJobType());

            // Candidates, and the marks on this round.
            Map<Long, Long> candidates = new LinkedHashMap<>();   // applicationId -> studentUserId
            jdbc.query("""
                    SELECT id, student_user_id FROM job_applications
                     WHERE posting_id = :p AND status = 'SHORTLISTED' AND is_deleted = false ORDER BY id
                    """, new MapSqlParameterSource("p", postingId),
                    rs -> { candidates.put(rs.getLong(1), rs.getLong(2)); });
            Map<Long, RoundResult> marks = new HashMap<>();
            resultRepository.findByRoundId(roundId).forEach(r -> marks.put(r.getApplicationId(), r));

            Instant now = Instant.now();
            Timestamp at = Timestamp.from(now);
            List<SqlParameterSource> history = new ArrayList<>();

            // Marks on students who are no longer candidates are dropped.
            List<Long> stale = marks.keySet().stream().filter(id -> !candidates.containsKey(id)).toList();
            for (Long id : stale) {
                history.add(historyRow(roundId, id, marks.get(id).getOutcome(), null, RoundChangeKind.CLEAR,
                        RoundMarkSource.PUBLISH_DEFAULT, "No longer a candidate when the round was published",
                        actor.userId(), at));
            }
            if (!stale.isEmpty()) {
                jdbc.update("DELETE FROM round_results WHERE round_id = :r AND application_id IN (:ids)",
                        new MapSqlParameterSource("r", roundId).addValue("ids", stale));
            }

            List<Long> unmarked = candidates.keySet().stream().filter(id -> !marks.containsKey(id)).toList();
            if (!unmarked.isEmpty() && !Boolean.TRUE.equals(req.confirmUnmarked())) {
                throw new AdminExceptions.RoundStateException("UNMARKED_CANDIDATES",
                        unmarked.size() + " candidate(s) are not marked. Confirm to publish them as not advanced, "
                                + "or mark them first.");
            }
            if (candidates.isEmpty()) {
                throw new AdminExceptions.RoundStateException("NO_CANDIDATES",
                        "Nobody is left in this drive to take this round. End the drive instead.");
            }

            Map<RoundOutcome, List<Long>> byOutcome = new EnumMap<>(RoundOutcome.class);
            for (RoundOutcome o : RoundOutcome.values()) {
                byOutcome.put(o, new ArrayList<>());
            }
            candidates.keySet().forEach(id -> {
                RoundResult m = marks.get(id);
                byOutcome.get(m == null ? RoundOutcome.NOT_ADVANCED : m.getOutcome()).add(id);
            });
            List<Long> advanced = byOutcome.get(RoundOutcome.ADVANCED);
            if (finalRound) {
                offers.checkCanRecord(advanced, offerType);
            }

            // Unmarked -> recorded as not advanced, and said to be a default.
            if (!unmarked.isEmpty()) {
                List<SqlParameterSource> rows = new ArrayList<>();
                for (Long id : unmarked) {
                    rows.add(new MapSqlParameterSource()
                            .addValue("round", roundId).addValue("posting", postingId).addValue("app", id)
                            .addValue("student", candidates.get(id))
                            .addValue("outcome", RoundOutcome.NOT_ADVANCED.name())
                            .addValue("source", RoundMarkSource.PUBLISH_DEFAULT.name())
                            .addValue("by", actor.userId()).addValue("at", at));
                    history.add(historyRow(roundId, id, null, RoundOutcome.NOT_ADVANCED, RoundChangeKind.PUBLISH_DEFAULT,
                            RoundMarkSource.PUBLISH_DEFAULT, "Unmarked when the round was published", actor.userId(), at));
                }
                jdbc.batchUpdate("""
                        INSERT INTO round_results (round_id, posting_id, application_id, student_user_id,
                                                   outcome, source, marked_by, marked_at, version)
                        VALUES (:round, :posting, :app, :student, :outcome, :source, :by, :at, 0)
                        """, rows.toArray(SqlParameterSource[]::new));
            }
            if (!history.isEmpty()) {
                RoundMarkingService.insertHistory(jdbc, history);
            }

            // Applications: everyone who did not advance is out; on the final
            // round, everyone who did is offered. History first, then the update.
            String roundLabel = RoundNotifier.roundName(round);
            moveAll(byOutcome.get(RoundOutcome.NOT_ADVANCED), ApplicationStatus.SHORTLISTED, ApplicationStatus.REJECTED,
                    "Not advanced in " + roundLabel, actor, now);
            moveAll(byOutcome.get(RoundOutcome.ABSENT), ApplicationStatus.SHORTLISTED, ApplicationStatus.REJECTED,
                    "Absent in " + roundLabel, actor, now);
            int offered = 0;
            if (finalRound && !advanced.isEmpty()) {
                moveAll(advanced, ApplicationStatus.SHORTLISTED, ApplicationStatus.OFFERED,
                        "Cleared the final round, " + roundLabel, actor, now);
                posting = postingRepository.findById(postingId).orElseThrow();
                offered = offers.record(posting, advanced, offerType, actor.userId());
            }

            // The bulk updates cleared the persistence context: re-read, then stamp.
            round = roundRepository.findById(roundId).orElseThrow();
            round.setStatus(RoundStatus.PUBLISHED);
            round.setFinalRound(finalRound);
            round.setPublishedAt(now);
            round.setPublishedBy(actor.userId());
            round.setUpdatedBy(actor.userId());
            round.setMarksVersion(round.getMarksVersion() + 1);
            round = roundRepository.saveAndFlush(round);

            posting = postingRepository.findById(postingId).orElseThrow();
            if (posting.getStatus() == PostingStatus.APPLICATIONS_CLOSED) {
                posting.setStatus(PostingStatus.IN_PROGRESS);
                postingRepository.save(posting);
            }

            // Notices: one batch per outcome. The next round's details go to
            // everyone who advanced, so they know where to be.
            DriveRound next = rounds.stream().filter(r -> r.getSeq() == seq + 1)
                    .findFirst().orElse(null);
            notifier.roundResult(postingId, round, RoundOutcome.NOT_ADVANCED,
                    students(candidates, byOutcome.get(RoundOutcome.NOT_ADVANCED)), "");
            notifier.roundResult(postingId, round, RoundOutcome.ABSENT,
                    students(candidates, byOutcome.get(RoundOutcome.ABSENT)), "");
            if (finalRound) {
                notifier.offered(postingId, students(candidates, advanced), opportunity(offerType));
            } else {
                notifier.roundResult(postingId, round, RoundOutcome.ADVANCED, students(candidates, advanced),
                        RoundNotifier.nextStep(next));
            }

            log.info("Round published: postingId={} roundId={} seq={} final={} advanced={} notAdvanced={} absent={} "
                            + "defaulted={} staleMarksDropped={} offered={} actorId={}",
                    postingId, roundId, round.getSeq(), finalRound, advanced.size(),
                    byOutcome.get(RoundOutcome.NOT_ADVANCED).size(), byOutcome.get(RoundOutcome.ABSENT).size(),
                    unmarked.size(), stale.size(), offered, actor.userId());

            RoundView view = DriveRoundService.view(round, false, last, support.names(List.of(actor.userId()))
                    .get(actor.userId()), support.counts(round, false));
            return new PublishRoundResult(view, advanced.size(), byOutcome.get(RoundOutcome.NOT_ADVANCED).size(),
                    byOutcome.get(RoundOutcome.ABSENT).size(), unmarked.size(), offered, stale.size());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not publish roundId={} of postingId={} actorId={}: {}",
                    roundId, postingId, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not publish the round. Nothing was changed; "
                    + "please try again.");
        }
    }

    // ------------------------------------------------------------- correction

    @Transactional
    public RoundDtos.SheetRow correct(UserPrincipal actor, Long postingId, Long roundId, CorrectionRequest req) {
        try {
            JobPosting posting = support.postingExclusive(postingId);
            support.authorise(actor, postingId, Access.MANAGE);
            support.requireRoundsRunning(posting);
            DriveRound round = support.lockRound(postingId, roundId);
            if (round.getStatus() != RoundStatus.PUBLISHED) {
                throw new AdminExceptions.RoundStateException("NOT_PUBLISHED",
                        "This round is not published yet; change the mark on the round sheet instead.");
            }
            List<DriveRound> rounds = roundRepository.findByPostingIdOrderBySeqAsc(postingId);
            DriveRound next = rounds.stream().filter(r -> r.getSeq() == round.getSeq() + 1).findFirst().orElse(null);
            if (next != null && next.getStatus() == RoundStatus.PUBLISHED) {
                throw new AdminExceptions.RoundStateException("ROUND_FROZEN",
                        RoundNotifier.roundName(next) + " is already published, so results of "
                                + RoundNotifier.roundName(round) + " can no longer be corrected.");
            }
            String reason = req.reason().trim();
            RoundResult result = resultRepository.findByRoundIdAndApplicationIdIn(roundId, List.of(req.applicationId()))
                    .stream().findFirst()
                    .orElseThrow(() -> new AdminExceptions.InvalidRequestException(
                            "This student did not take " + RoundNotifier.roundName(round) + "."));
            if (result.getVersion() != req.expectedVersion()) {
                throw new AdminExceptions.RoundStateException("CHANGED",
                        "This result was changed by someone else a moment ago. Reload and check it again.");
            }
            RoundOutcome from = result.getOutcome();
            RoundOutcome to = req.outcome();
            if (from == to) {
                throw new AdminExceptions.InvalidRequestException("The result is already " + to.label() + ".");
            }
            Instant now = Instant.now();
            Timestamp at = Timestamp.from(now);
            Long app = req.applicationId();
            Long student = result.getStudentUserId();

            Optional<RoundRules.StatusMove> move = RoundRules.moveForCorrection(from, to, round.isFinalRound());
            OfferType offerType = null;
            if (move.isPresent()) {
                RoundRules.StatusMove m = move.get();
                String status = jdbc.queryForObject("SELECT status FROM job_applications WHERE id = :id",
                        new MapSqlParameterSource("id", app), String.class);
                if (!m.from().name().equals(status)) {
                    // E.g. disqualified since, or the student has answered the offer.
                    throw new AdminExceptions.RoundStateException("APPLICATION_MOVED",
                            "This student's application is now " + status + ", so this result cannot be "
                                    + "corrected here.");
                }
                if (round.isFinalRound() && to.advances()) {
                    OfferType recorded = offers.typeRecordedOn(postingId);
                    offerType = recorded != null ? recorded
                            : posting.getJobType() == null ? OfferType.OTHER : OfferTypes.forJobType(posting.getJobType());
                    offers.checkCanRecord(List.of(app), offerType);
                }
                if (round.isFinalRound() && from.advances()) {
                    offers.revoke(app, "Final-round result corrected: " + reason, actor.userId());
                }
                moveAll(List.of(app), m.from(), m.to(),
                        "Result of " + RoundNotifier.roundName(round) + " corrected to " + to.label() + ": " + reason,
                        actor, now);
                if (round.isFinalRound() && to.advances()) {
                    posting = postingRepository.findById(postingId).orElseThrow();
                    offers.record(posting, List.of(app), offerType, actor.userId());
                }
                if (next != null) {
                    if (from.advances()) {
                        // Out of the next round: any draft mark there goes.
                        List<RoundResult> drafts = resultRepository.findByRoundIdAndApplicationIdIn(next.getId(), List.of(app));
                        if (!drafts.isEmpty()) {
                            jdbc.update("DELETE FROM round_results WHERE round_id = :r AND application_id = :a",
                                    new MapSqlParameterSource("r", next.getId()).addValue("a", app));
                            RoundMarkingService.insertHistory(jdbc, List.of(historyRow(next.getId(), app,
                                    drafts.get(0).getOutcome(), null, RoundChangeKind.CASCADE_CLEARED,
                                    RoundMarkSource.PORTAL, "Taken out of this round by a correction to "
                                            + RoundNotifier.roundName(round) + ": " + reason, actor.userId(), at)));
                        }
                    }
                    // Either way the next round's candidate list changed: a
                    // publish reviewed against the old list must be redone.
                    RoundMarkingService.bump(jdbc, next.getId());
                }
            }

            jdbc.update("""
                    UPDATE round_results SET outcome = :o, marked_by = :by, marked_at = :at, version = version + 1
                     WHERE id = :id
                    """, new MapSqlParameterSource("o", to.name()).addValue("by", actor.userId())
                    .addValue("at", at).addValue("id", result.getId()));
            RoundMarkingService.insertHistory(jdbc, List.of(historyRow(roundId, app, from, to,
                    RoundChangeKind.CORRECTION, RoundMarkSource.PORTAL, reason, actor.userId(), at)));
            RoundMarkingService.bump(jdbc, roundId);

            String nextStep;
            if (to.advances()) {
                nextStep = round.isFinalRound()
                        ? "You have been selected. The company will contact you about the offer."
                        : RoundNotifier.nextStep(next);
            } else {
                nextStep = "You are not continuing in this drive.";
            }
            notifier.corrected(postingId, round, student, from, to, reason, nextStep);
            if (round.isFinalRound() && to.advances()) {
                notifier.offered(postingId, List.of(student), opportunity(offerType));
            }
            log.info("Round result corrected: postingId={} roundId={} applicationId={} {} -> {} moved={} actorId={} reason={}",
                    postingId, roundId, app, from, to, move.isPresent(), actor.userId(), reason);
            return new RoundDtos.SheetRow(app, student, null, null, null, null, null, to, result.getVersion() + 1,
                    RoundMarkSource.PORTAL.name(), support.names(List.of(actor.userId())).get(actor.userId()),
                    now, move.map(m -> m.to().name()).orElse(null), true);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not correct a result in roundId={} of postingId={} actorId={}: {}",
                    roundId, postingId, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not save the correction. Nothing was changed; "
                    + "please try again.");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void moveAll(List<Long> ids, ApplicationStatus from, ApplicationStatus to, String reason,
                         UserPrincipal actor, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        applicationRepository.recordHistoryForIds(ids, from.name(), to.name(), now, actor.userId(),
                ActorKind.TNP.name(), reason);
        int moved = applicationRepository.updateStatusForIds(ids, from, to, actor.userId());
        if (moved != ids.size()) {
            // Every id was read under the posting's exclusive lock, so this means
            // something moved an application without taking it. Roll back.
            throw new IllegalStateException("Expected to move " + ids.size() + " applications " + from + " -> " + to
                    + " but moved " + moved);
        }
    }

    private static List<Long> students(Map<Long, Long> candidates, List<Long> applicationIds) {
        return applicationIds.stream().map(candidates::get).toList();
    }

    static SqlParameterSource historyRow(Long roundId, Long appId, RoundOutcome from, RoundOutcome to,
                                         RoundChangeKind kind, RoundMarkSource source, String reason,
                                         Long by, Timestamp at) {
        return new MapSqlParameterSource()
                .addValue("round", roundId).addValue("app", appId)
                .addValue("from", from == null ? null : from.name())
                .addValue("to", to == null ? null : to.name())
                .addValue("kind", kind.name()).addValue("source", source.name())
                .addValue("reason", reason).addValue("by", by).addValue("at", at);
    }

    /** "Internship with PPO" */
    static String opportunity(OfferType type) {
        if (type == null) {
            return "Selected";
        }
        return switch (type) {
            case PPO -> "Pre-placement offer (PPO)";
            case FULL_TIME -> "Full-time placement";
            case INTERNSHIP -> "Internship";
            case INTERNSHIP_WITH_PPO -> "Internship with PPO";
            case APPRENTICESHIP -> "Apprenticeship";
            case COMPETITION -> "Competition";
            case COMPETITION_WITH_PPO -> "Competition with PPO";
            case COMPETITION_WITH_INTERNSHIP -> "Competition with internship";
            case OTHER -> "Selected";
        };
    }
}

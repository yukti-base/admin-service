package org.yuktisetu.adminservice.rounds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.rounds.RoundDtos.EmailResolution;
import org.yuktisetu.adminservice.rounds.RoundDtos.MarkConflict;
import org.yuktisetu.adminservice.rounds.RoundDtos.MarkItem;
import org.yuktisetu.adminservice.rounds.RoundDtos.MarkRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.MarkResult;
import org.yuktisetu.adminservice.rounds.RoundDtos.MatchedStudent;
import org.yuktisetu.adminservice.rounds.RoundDtos.NotInRound;
import org.yuktisetu.adminservice.rounds.RoundSupport.Access;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.DriveRound;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.RoundResult;
import org.yuktisetu.drive.model.EmailList;
import org.yuktisetu.drive.model.RoundChangeKind;
import org.yuktisetu.drive.model.RoundMarkSource;
import org.yuktisetu.drive.model.RoundOutcome;
import org.yuktisetu.drive.model.RoundStatus;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.RoundResultRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Draft marks on the current round, and resolving a company's email list.
 *
 * How concurrent markers are kept from overwriting each other (decided
 * 2026-10-30: every edge case explicit):
 *
 *  - Every mark names the version of the result it saw (null = "unmarked when
 *    I looked"). If the result moved since, the mark is NOT applied; the marker
 *    gets back what is there now and who set it, and decides again.
 *  - Marking the value that is already there is a no-op success, so two
 *    volunteers ticking the same student "advanced" never conflict.
 *  - All marks on a round take the round's row lock (after a shared lock on the
 *    posting), so the read-compare-write below cannot interleave with another
 *    marker, a publish or a correction. One mark is a few milliseconds; a hall
 *    of volunteers queues for microseconds.
 *  - Only the drive's current round can be marked, and only its candidates --
 *    a student disqualified a moment ago is refused, not silently marked.
 *  - Every applied change bumps the round's marksVersion, which is what stops a
 *    publish going out over marks the coordinator never saw.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoundMarkingService {

    private final RoundSupport support;
    private final RoundResultRepository resultRepository;
    private final JobApplicationRepository applicationRepository;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public MarkResult mark(UserPrincipal actor, Long postingId, Long roundId, MarkRequest req) {
        try {
            JobPosting posting = support.postingShared(postingId);
            support.authorise(actor, postingId, Access.MARK);
            support.requireRoundsRunning(posting);
            DriveRound round = support.lockRound(postingId, roundId);
            requireCurrent(round);

            RoundMarkSource source = req.source() == null ? RoundMarkSource.PORTAL : req.source();
            if (source == RoundMarkSource.PUBLISH_DEFAULT) {
                throw new AdminExceptions.InvalidRequestException("That source is set by publishing, not by marking.");
            }
            // The last item for an application wins inside one request; a list
            // naming a student twice is not a conflict with itself.
            Map<Long, MarkItem> items = new LinkedHashMap<>();
            for (MarkItem item : req.items()) {
                items.put(item.applicationId(), item);
            }

            Map<Long, Object[]> apps = applications(postingId, items.keySet());
            Map<Long, RoundResult> existing = new HashMap<>();
            resultRepository.findByRoundIdAndApplicationIdIn(roundId, items.keySet())
                    .forEach(r -> existing.put(r.getApplicationId(), r));

            List<Object[]> rawConflicts = new ArrayList<>();
            List<SqlParameterSource> upserts = new ArrayList<>();
            List<SqlParameterSource> history = new ArrayList<>();
            List<Long> clears = new ArrayList<>();
            int unchanged = 0;
            Instant now = Instant.now();

            for (MarkItem item : items.values()) {
                Long appId = item.applicationId();
                Object[] app = apps.get(appId);
                RoundResult cur = existing.get(appId);
                if (app == null || !"SHORTLISTED".equals(app[1])) {
                    rawConflicts.add(new Object[]{appId, cur, "NOT_A_CANDIDATE", app == null ? null : app[1],
                            app == null ? null : app[0]});
                    continue;
                }
                Integer curVersion = cur == null ? null : cur.getVersion();
                RoundOutcome curOutcome = cur == null ? null : cur.getOutcome();
                if (curOutcome == item.outcome()) {
                    unchanged++;
                    continue;
                }
                if (!Objects.equals(curVersion, item.expectedVersion())) {
                    rawConflicts.add(new Object[]{appId, cur, "CHANGED", app[1], app[0]});
                    continue;
                }
                if (item.outcome() == null) {
                    clears.add(appId);
                } else {
                    upserts.add(new MapSqlParameterSource()
                            .addValue("round", roundId).addValue("posting", postingId)
                            .addValue("app", appId).addValue("student", app[0])
                            .addValue("outcome", item.outcome().name()).addValue("source", source.name())
                            .addValue("by", actor.userId()).addValue("at", java.sql.Timestamp.from(now)));
                }
                history.add(new MapSqlParameterSource()
                        .addValue("round", roundId).addValue("app", appId)
                        .addValue("from", curOutcome == null ? null : curOutcome.name())
                        .addValue("to", item.outcome() == null ? null : item.outcome().name())
                        .addValue("kind", (item.outcome() == null ? RoundChangeKind.CLEAR : RoundChangeKind.MARK).name())
                        .addValue("source", source.name()).addValue("reason", null)
                        .addValue("by", actor.userId()).addValue("at", java.sql.Timestamp.from(now)));
            }

            if (!upserts.isEmpty()) {
                jdbc.batchUpdate("""
                        INSERT INTO round_results (round_id, posting_id, application_id, student_user_id,
                                                   outcome, source, marked_by, marked_at, version)
                        VALUES (:round, :posting, :app, :student, :outcome, :source, :by, :at, 0)
                        ON CONFLICT (round_id, application_id) DO UPDATE
                           SET outcome = EXCLUDED.outcome, source = EXCLUDED.source,
                               marked_by = EXCLUDED.marked_by, marked_at = EXCLUDED.marked_at,
                               version = round_results.version + 1
                        """, upserts.toArray(SqlParameterSource[]::new));
            }
            if (!clears.isEmpty()) {
                jdbc.update("DELETE FROM round_results WHERE round_id = :r AND application_id IN (:ids)",
                        new MapSqlParameterSource("r", roundId).addValue("ids", clears));
            }
            if (!history.isEmpty()) {
                insertHistory(jdbc, history);
            }
            List<MarkConflict> conflicts = conflicts(rawConflicts);
            long marksVersion = round.getMarksVersion();
            if (!history.isEmpty()) {
                marksVersion = bump(jdbc, roundId);
            }
            log.info("Round marks: postingId={} roundId={} actorId={} source={} applied={} unchanged={} conflicts={}",
                    postingId, roundId, actor.userId(), source, history.size(), unchanged, conflicts.size());
            return new MarkResult(history.size(), unchanged, conflicts, marksVersion);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not mark roundId={} of postingId={} actorId={}: {}",
                    roundId, postingId, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not save the marks. Please try again.");
        }
    }

    /**
     * Resolves a pasted list, or an uploaded sheet the portal turned into text,
     * against this round. Changes nothing: the portal shows the result, and the
     * marks follow as an ordinary MarkRequest carrying the versions read here --
     * so a volunteer who changed one of those students in between is a conflict,
     * not an overwrite.
     */
    @Transactional(readOnly = true)
    public EmailResolution resolveEmails(UserPrincipal actor, Long postingId, Long roundId, String text) {
        try {
            JobPosting posting = support.posting(postingId);
            support.authorise(actor, postingId, Access.MARK);
            support.requireRoundsRunning(posting);
            DriveRound round = support.round(postingId, roundId);
            requireCurrent(round);

            EmailList.Parsed parsed;
            try {
                parsed = EmailList.parse(text);
            } catch (IllegalArgumentException e) {
                throw new AdminExceptions.InvalidRequestException(e.getMessage());
            }
            Resolved r = resolve(applicationRepository, jdbc, postingId, parsed);
            Map<Long, RoundResult> marks = new HashMap<>();
            if (!r.candidateApps.isEmpty()) {
                resultRepository.findByRoundIdAndApplicationIdIn(roundId, r.candidateApps.keySet())
                        .forEach(m -> marks.put(m.getApplicationId(), m));
            }
            List<MatchedStudent> matched = new ArrayList<>();
            r.candidateApps.forEach((appId, email) -> {
                RoundResult m = marks.get(appId);
                matched.add(new MatchedStudent(appId, email, r.names.get(email),
                        m == null ? null : m.getOutcome(), m == null ? null : m.getVersion()));
            });
            log.info("Email list resolved: postingId={} roundId={} listed={} matched={} noAccount={} notApplied={} notInRound={}",
                    postingId, roundId, parsed.emails().size(), matched.size(), r.noAccount.size(),
                    r.notApplied.size(), r.notInRound.size());
            return new EmailResolution(parsed.emails().size(), matched, r.noAccount, r.notApplied, r.notInRound,
                    parsed.duplicates(), parsed.unreadable());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not resolve an email list for roundId={} of postingId={}: {}",
                    roundId, postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not read that list. Please try again.");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Where each listed email landed, against the drive's SHORTLISTED candidates. */
    record Resolved(Map<Long, String> candidateApps, Map<String, String> names, List<String> noAccount,
                    List<String> notApplied, List<NotInRound> notInRound) {}

    static Resolved resolve(JobApplicationRepository applications, NamedParameterJdbcTemplate jdbc,
                            Long postingId, EmailList.Parsed parsed) {
        Map<Long, String> candidateApps = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        List<String> noAccount = new ArrayList<>();
        List<String> notApplied = new ArrayList<>();
        List<NotInRound> notInRound = new ArrayList<>();
        if (parsed.emails().isEmpty()) {
            return new Resolved(candidateApps, names, noAccount, notApplied, notInRound);
        }
        Map<String, Object[]> byEmail = new HashMap<>();
        Map<Long, String> emailByUser = new HashMap<>();
        for (Object[] row : applications.resolveEmails(postingId, parsed.emails())) {
            byEmail.put((String) row[0], row);
            emailByUser.put(((Number) row[1]).longValue(), (String) row[0]);
        }
        if (!emailByUser.isEmpty()) {
            jdbc.query("SELECT id, first_name, last_name FROM users WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", emailByUser.keySet()),
                    rs -> { names.put(emailByUser.get(rs.getLong(1)), RoundSupport.name(rs.getString(2), rs.getString(3))); });
        }
        for (String email : parsed.emails()) {
            Object[] row = byEmail.get(email);
            if (row == null) {
                noAccount.add(email);
            } else if (row[2] == null) {
                notApplied.add(email);
            } else if (!"SHORTLISTED".equals(row[3])) {
                notInRound.add(new NotInRound(email, names.get(email), (String) row[3]));
            } else {
                candidateApps.put(((Number) row[2]).longValue(), email);
            }
        }
        return new Resolved(candidateApps, names, noAccount, notApplied, notInRound);
    }

    private void requireCurrent(DriveRound round) {
        if (round.getStatus() == RoundStatus.PUBLISHED) {
            throw new AdminExceptions.RoundStateException("ROUND_PUBLISHED",
                    "This round's results are already published. A coordinator can correct a result, with a reason.");
        }
        if (!support.isCurrent(round)) {
            throw new AdminExceptions.RoundStateException("NOT_CURRENT_ROUND",
                    "Only the drive's current round can be marked. Publish the earlier round first.");
        }
    }

    /** applicationId -> [studentUserId, status] for these ids on this drive. */
    private Map<Long, Object[]> applications(Long postingId, java.util.Collection<Long> ids) {
        Map<Long, Object[]> out = new HashMap<>();
        jdbc.query("""
                SELECT id, student_user_id, status FROM job_applications
                 WHERE id IN (:ids) AND posting_id = :p AND is_deleted = false
                """, new MapSqlParameterSource("ids", ids).addValue("p", postingId),
                rs -> { out.put(rs.getLong(1), new Object[]{rs.getLong(2), rs.getString(3)}); });
        return out;
    }

    /** [appId, current result, reason, status, studentUserId] -> conflicts, with every name in ONE query. */
    private List<MarkConflict> conflicts(List<Object[]> raw) {
        if (raw.isEmpty()) {
            return List.of();
        }
        List<Long> people = new ArrayList<>();
        for (Object[] c : raw) {
            RoundResult cur = (RoundResult) c[1];
            if (c[4] != null) people.add((Long) c[4]);
            if (cur != null) people.add(cur.getMarkedBy());
        }
        Map<Long, String> who = support.names(people);
        List<MarkConflict> out = new ArrayList<>(raw.size());
        for (Object[] c : raw) {
            RoundResult cur = (RoundResult) c[1];
            out.add(new MarkConflict((Long) c[0], c[4] == null ? null : who.get((Long) c[4]), (String) c[2],
                    cur == null ? null : cur.getOutcome(), cur == null ? null : cur.getVersion(),
                    cur == null ? null : who.get(cur.getMarkedBy()), cur == null ? null : cur.getMarkedAt(),
                    (String) c[3]));
        }
        return out;
    }

    static void insertHistory(NamedParameterJdbcTemplate jdbc, List<SqlParameterSource> rows) {
        jdbc.batchUpdate("""
                INSERT INTO round_result_history (round_id, application_id, from_outcome, to_outcome,
                                                  kind, source, reason, changed_by, changed_at)
                VALUES (:round, :app, :from, :to, :kind, :source, :reason, :by, :at)
                """, rows.toArray(SqlParameterSource[]::new));
    }

    static long bump(NamedParameterJdbcTemplate jdbc, Long roundId) {
        Long v = jdbc.queryForObject(
                "UPDATE drive_rounds SET marks_version = marks_version + 1 WHERE id = :id RETURNING marks_version",
                new MapSqlParameterSource("id", roundId), Long.class);
        return v == null ? 0 : v;
    }
}

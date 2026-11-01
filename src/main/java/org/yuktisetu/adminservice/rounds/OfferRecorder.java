package org.yuktisetu.adminservice.rounds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.core.time.IstTime;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.model.OfferSource;
import org.yuktisetu.drive.model.OfferStatus;
import org.yuktisetu.drive.model.OfferStatusTransitions;
import org.yuktisetu.drive.model.OfferType;
import org.yuktisetu.drive.model.OfferTypes;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records what a student holds after clearing a drive's final round.
 *
 * Not an offer letter -- the company sends that, outside the app (decided
 * 2026-10-30). This row is the platform's own record that the student was
 * selected, for which kind of opportunity: it is what the re-application rules
 * read (an intern cannot sit another internship drive; a placed student needs
 * the CTC uplift), and what placement statistics will count.
 *
 * The package is the drive's advertised one (the top of the range, if a range
 * was given): the best the platform knows until the TnP records the real figure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfferRecorder {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Refuses, naming every student, when any of them cannot be recorded: no
     * active college (the offer row needs one), or -- for an internship -- a live
     * internship already held, which the database's internship lock would reject.
     * Nothing is written unless everyone can be.
     */
    public void checkCanRecord(Collection<Long> applicationIds, OfferType type) {
        if (applicationIds.isEmpty()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        Map<Long, Object[]> facts = facts(applicationIds);
        for (Long appId : applicationIds) {
            Object[] f = facts.get(appId);
            if (f == null || f[1] == null) {
                problems.add((f == null ? "Application " + appId : f[2]) + " has no active college assignment");
            }
        }
        if (OfferTypes.grantsInternship(type)) {
            List<Long> students = facts.values().stream().map(f -> (Long) f[0]).toList();
            jdbc.query("""
                    SELECT o.student_user_id, c.name, u.first_name, u.last_name
                      FROM placement_offers o
                      JOIN companies c ON c.id = o.company_id
                      JOIN users u ON u.id = o.student_user_id
                     WHERE o.active_internship_lock IN (:students) AND o.is_deleted = false
                    """, new MapSqlParameterSource("students", students),
                    rs -> { problems.add(RoundSupport.name(rs.getString(3), rs.getString(4))
                            + " already holds a live internship at " + rs.getString(2)); });
        }
        if (!problems.isEmpty()) {
            throw new AdminExceptions.RoundStateException("CANNOT_RECORD_OFFER",
                    "These students cannot be marked offered: " + String.join("; ", problems)
                            + ". Mark them not advanced, or resolve this first.");
        }
    }

    /** Inserts one OFFERED row per application, in one batch. Call checkCanRecord first. */
    public int record(JobPosting posting, Collection<Long> applicationIds, OfferType type, Long actorUserId) {
        if (applicationIds.isEmpty()) {
            return 0;
        }
        Map<Long, Object[]> facts = facts(applicationIds);
        Timestamp now = Timestamp.from(Instant.now());
        Date today = Date.valueOf(LocalDate.now(IstTime.ZONE));
        List<SqlParameterSource> rows = new ArrayList<>();
        for (Long appId : applicationIds) {
            Object[] f = facts.get(appId);
            Long student = (Long) f[0];
            rows.add(new MapSqlParameterSource()
                    .addValue("now", now).addValue("by", actorUserId)
                    .addValue("student", student).addValue("college", f[1])
                    .addValue("app", appId).addValue("posting", posting.getId())
                    .addValue("company", posting.getCompany().getId())
                    .addValue("type", type.name()).addValue("status", OfferStatus.OFFERED.name())
                    .addValue("ctc", posting.getCtcMax() != null ? posting.getCtcMax() : posting.getCtcMin())
                    .addValue("currency", posting.getCtcCurrency() == null ? "INR" : posting.getCtcCurrency())
                    .addValue("basis", posting.getPackageBasis() == null ? "CTC" : posting.getPackageBasis().name())
                    .addValue("stipend", posting.getStipendAmount())
                    .addValue("period", posting.getStipendPeriod() == null ? null : posting.getStipendPeriod().name())
                    .addValue("role", posting.getTitle())
                    .addValue("today", today)
                    .addValue("counts", posting.isMarksStudentPlaced())
                    .addValue("source", OfferSource.PORTAL.name())
                    .addValue("lock", OfferStatusTransitions.internshipLockValue(type, OfferStatus.OFFERED, student)));
        }
        try {
            jdbc.batchUpdate("""
                    INSERT INTO placement_offers (created_at, updated_at, created_by, updated_by, is_deleted,
                            student_user_id, college_id, application_id, posting_id, company_id,
                            offer_type, status, ctc_amount, ctc_currency, package_basis,
                            stipend_amount, stipend_period, role_title, offer_date,
                            counts_toward_placement, source, active_internship_lock)
                    VALUES (:now, :now, :by, :by, false,
                            :student, :college, :app, :posting, :company,
                            :type, :status, :ctc, :currency, :basis,
                            :stipend, :period, :role, :today,
                            :counts, :source, :lock)
                    """, rows.toArray(SqlParameterSource[]::new));
        } catch (DataIntegrityViolationException e) {
            // Another drive recorded an internship for one of these students
            // between the check and here. The whole publish rolls back.
            log.warn("Offer insert refused by a constraint for postingId={}: {}", posting.getId(), e.getMessage());
            throw new AdminExceptions.RoundStateException("CANNOT_RECORD_OFFER",
                    "One of these students was given a live internship on another drive a moment ago. "
                            + "Reload and try again.");
        }
        log.info("Offers recorded: postingId={} type={} count={}", posting.getId(), type, rows.size());
        return rows.size();
    }

    /** Revokes the live offer this drive recorded for one application, when its final-round result is corrected. */
    public int revoke(Long applicationId, String reason, Long actorUserId) {
        return jdbc.update("""
                UPDATE placement_offers
                   SET status = 'REVOKED', revoked_at = :now, revoke_reason = :reason,
                       active_internship_lock = NULL, updated_at = :now, updated_by = :by
                 WHERE application_id = :app AND status = 'OFFERED' AND is_deleted = false
                """, new MapSqlParameterSource("app", applicationId).addValue("reason", reason)
                .addValue("now", Timestamp.from(Instant.now())).addValue("by", actorUserId));
    }

    /** The offer type this drive recorded, if any is still live for the application. */
    public OfferType liveTypeFor(Long applicationId) {
        List<String> t = jdbc.queryForList("""
                SELECT offer_type FROM placement_offers
                 WHERE application_id = :app AND status = 'OFFERED' AND is_deleted = false
                """, new MapSqlParameterSource("app", applicationId), String.class);
        return t.isEmpty() ? null : OfferType.valueOf(t.get(0));
    }

    /** The type the drive's final round was published with, from any offer it recorded. */
    public OfferType typeRecordedOn(Long postingId) {
        List<String> t = jdbc.queryForList("""
                SELECT offer_type FROM placement_offers
                 WHERE posting_id = :p AND is_deleted = false
                 ORDER BY id LIMIT 1
                """, new MapSqlParameterSource("p", postingId), String.class);
        return t.isEmpty() ? null : OfferType.valueOf(t.get(0));
    }

    /** applicationId -> [studentUserId, collegeId or null, name], one query. */
    private Map<Long, Object[]> facts(Collection<Long> applicationIds) {
        Map<Long, Object[]> out = new HashMap<>();
        jdbc.query("""
                SELECT a.id, a.student_user_id, ura.college_id, u.first_name, u.last_name
                  FROM job_applications a
                  JOIN users u ON u.id = a.student_user_id
                  LEFT JOIN LATERAL (
                        SELECT x.college_id FROM user_role_assignments x
                         WHERE x.user_id = a.student_user_id AND x.role = 'STUDENT' AND x.is_active = true
                         ORDER BY x.id LIMIT 1) ura ON true
                 WHERE a.id IN (:ids)
                """, new MapSqlParameterSource("ids", applicationIds),
                rs -> {
                    long college = rs.getLong(3);
                    out.put(rs.getLong(1), new Object[]{rs.getLong(2), rs.wasNull() ? null : college,
                            RoundSupport.name(rs.getString(4), rs.getString(5))});
                });
        return out;
    }
}

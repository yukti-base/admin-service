package org.yuktisetu.adminservice.offers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.offers.OfferDtos.DriveResults;
import org.yuktisetu.adminservice.offers.OfferDtos.OfferView;
import org.yuktisetu.adminservice.offers.OfferDtos.ResultRow;
import org.yuktisetu.adminservice.offers.OfferDtos.WithdrawalView;
import org.yuktisetu.adminservice.rounds.RoundSupport;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.model.PostingStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Every student the drive shortlisted, with what the TnP needs after it ends
 * (decided 2026-11-01): contact details and PRN, CGPA, the composite score and
 * its breakdown, the resume they applied with, how far they got, and their offer.
 *
 * "Shortlisted" means shortlisted at any point -- someone rejected in round 2 is
 * still on this list, because the department report and the company's records
 * cover everyone who sat the rounds. One query for the whole page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriveResultsService {

    private final NamedParameterJdbcTemplate jdbc;
    private final RoundSupport support;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DriveResults results(UserPrincipal actor, Long postingId) {
        try {
            JobPosting posting = support.posting(postingId);
            support.authorise(actor, postingId, RoundSupport.Access.VIEW);
            List<Long> colleges = support.targetColleges(postingId);
            boolean canDecide = support.allowed(actor, colleges, RoundSupport.Access.MANAGE);

            List<ResultRow> rows = jdbc.query("""
                    SELECT a.id, u.id, u.first_name, u.last_name, u.email, u.phone, sp.prn, ura.college_name,
                           ura.dept_name, sp.cgpa, a.status, a.resume_url, a.resume_file_name,
                           s.composite, s.rank, s.meets_threshold, s.breakdown::text,
                           lr.seq, lr.name, lr.outcome,
                           o.id, o.status, o.offer_type, o.ctc_amount, o.ctc_currency, o.accepted_at, o.declined_at,
                           o.decline_reason, o.withdrawn_at, o.withdrawal_reason, o.revoked_at, o.revoke_reason,
                           w.id, w.status, w.reason, w.requested_at, w.decided_at, w.decision_note
                      FROM job_applications a
                      JOIN users u ON u.id = a.student_user_id
                      LEFT JOIN student_profiles sp ON sp.user_id = u.id
                      LEFT JOIN LATERAL (
                            SELECT c.name AS college_name, d.name AS dept_name
                              FROM user_role_assignments x
                              LEFT JOIN colleges c ON c.id = x.college_id
                              LEFT JOIN departments d ON d.id = x.dept_id
                             WHERE x.user_id = u.id AND x.role = 'STUDENT'
                             ORDER BY x.is_active DESC, x.id LIMIT 1) ura ON true
                      LEFT JOIN application_scores s ON s.application_id = a.id
                            AND s.run_id = (SELECT r.id FROM drive_score_runs r
                                             WHERE r.posting_id = a.posting_id AND r.superseded_at IS NULL
                                             ORDER BY r.id DESC LIMIT 1)
                      LEFT JOIN LATERAL (
                            SELECT d.seq, d.name, rr.outcome FROM round_results rr
                              JOIN drive_rounds d ON d.id = rr.round_id
                             WHERE rr.application_id = a.id AND d.status = 'PUBLISHED'
                             ORDER BY d.seq DESC LIMIT 1) lr ON true
                      LEFT JOIN LATERAL (
                            SELECT * FROM placement_offers po
                             WHERE po.application_id = a.id AND po.is_deleted = false
                             ORDER BY po.id DESC LIMIT 1) o ON true
                      LEFT JOIN LATERAL (
                            SELECT * FROM offer_withdrawal_requests wr
                             WHERE wr.offer_id = o.id ORDER BY wr.requested_at DESC LIMIT 1) w ON true
                     WHERE a.posting_id = :p AND a.is_deleted = false
                       AND (a.status IN ('SHORTLISTED', 'OFFERED', 'OFFER_ACCEPTED', 'OFFER_DECLINED')
                            OR EXISTS (SELECT 1 FROM application_status_history h
                                        WHERE h.application_id = a.id AND h.to_status = 'SHORTLISTED'))
                     ORDER BY CASE WHEN o.id IS NULL THEN 1 ELSE 0 END, lr.seq DESC NULLS LAST,
                              s.rank NULLS LAST, lower(u.first_name), lower(u.last_name)
                    """, new MapSqlParameterSource("p", postingId), (rs, i) -> {
                long offerId = rs.getLong(21);
                OfferView offer = rs.wasNull() ? null : new OfferView(offerId, rs.getString(22), rs.getString(23),
                        rs.getBigDecimal(24), rs.getString(25), instant(rs.getTimestamp(26)), instant(rs.getTimestamp(27)),
                        rs.getString(28), instant(rs.getTimestamp(29)), rs.getString(30), instant(rs.getTimestamp(31)),
                        rs.getString(32));
                long reqId = rs.getLong(33);
                WithdrawalView withdrawal = rs.wasNull() ? null : new WithdrawalView(reqId, rs.getString(34),
                        rs.getString(35), instant(rs.getTimestamp(36)), instant(rs.getTimestamp(37)), rs.getString(38));
                int seq = rs.getInt(18);
                Integer lastSeq = rs.wasNull() ? null : seq;
                int rank = rs.getInt(15);
                Integer rankOrNull = rs.wasNull() ? null : rank;
                return new ResultRow(
                        rs.getLong(1), rs.getLong(2), RoundSupport.name(rs.getString(3), rs.getString(4)),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getBigDecimal(10), rs.getString(11), rs.getString(12), rs.getString(13),
                        rs.getBigDecimal(14), rankOrNull, (Boolean) rs.getObject(16), breakdown(rs.getString(17)),
                        lastSeq, rs.getString(19), rs.getString(20), offer, withdrawal);
            });

            int offered = 0, accepted = 0, pending = 0;
            for (ResultRow r : rows) {
                if (r.offer() != null) {
                    offered++;
                    if ("ACCEPTED".equals(r.offer().status())) {
                        accepted++;
                    }
                }
                if (r.withdrawal() != null && "PENDING".equals(r.withdrawal().status())) {
                    pending++;
                }
            }
            return new DriveResults(postingId, posting.getStatus().name(),
                    posting.getStatus() == PostingStatus.COMPLETED, rows.size(), offered, accepted, pending,
                    canDecide, actor.hasRole("IT_ADMIN"), rows);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not load results for postingId={} actorId={}: {}", postingId, actor.userId(),
                    e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load the drive's results. Please try again.");
        }
    }

    private Map<String, Object> breakdown(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Unreadable score breakdown: {}", e.getMessage());
            return null;
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

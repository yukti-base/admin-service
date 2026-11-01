package org.yuktisetu.adminservice.rounds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.rounds.RoundDtos.EndDriveRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.EndDriveResult;
import org.yuktisetu.adminservice.rounds.RoundSupport.Access;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.model.ActorKind;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.RoundOutcome;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.messaging.events.NotificationDispatchEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ending a drive: the TnP's "End Drive" (SSOT 5.6.3).
 *
 * The drive is COMPLETED and its results final. Students still in it -- the
 * company stopped after round 2 of 3 -- are told the drive ended without a
 * selection for them, but only after the TnP confirmed that, because ending a
 * drive with people still in it is usually a mistake.
 *
 * Then each department's faculty coordinators get their own department's list:
 * PRN, name, email, how far each student got and the outcome. One batched
 * dispatch whose recipients each carry their own rows; a coordinator of two
 * departments gets one message with both. Only coordinators whose assignment is
 * active and whose account is active are counted -- a department with none is
 * reported back to the TnP so it does not silently go unreported.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriveEndService {

    private final RoundSupport support;
    private final JobPostingRepository postingRepository;
    private final JobApplicationRepository applicationRepository;
    private final RoundNotifier notifier;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public EndDriveResult end(UserPrincipal actor, Long postingId, EndDriveRequest req) {
        try {
            JobPosting posting = support.postingExclusive(postingId);
            support.authorise(actor, postingId, Access.MANAGE);
            support.requireRoundsRunning(posting);

            Map<Long, Long> stillIn = new LinkedHashMap<>();   // applicationId -> studentUserId
            jdbc.query("""
                    SELECT id, student_user_id FROM job_applications
                     WHERE posting_id = :p AND status = 'SHORTLISTED' AND is_deleted = false
                    """, new MapSqlParameterSource("p", postingId),
                    rs -> { stillIn.put(rs.getLong(1), rs.getLong(2)); });
            if (!stillIn.isEmpty() && !Boolean.TRUE.equals(req.confirmUnfinished())) {
                throw new AdminExceptions.RoundStateException("UNFINISHED_CANDIDATES",
                        stillIn.size() + " student(s) are still in this drive. Ending it now tells them it ended "
                                + "without a selection. Confirm to end it anyway.");
            }

            Instant now = Instant.now();
            if (!stillIn.isEmpty()) {
                List<Long> ids = new ArrayList<>(stillIn.keySet());
                applicationRepository.recordHistoryForIds(ids, ApplicationStatus.SHORTLISTED.name(),
                        ApplicationStatus.REJECTED.name(), now, actor.userId(), ActorKind.TNP.name(),
                        "Drive ended before the remaining rounds");
                applicationRepository.updateStatusForIds(ids, ApplicationStatus.SHORTLISTED,
                        ApplicationStatus.REJECTED, actor.userId());
            }

            posting = postingRepository.findById(postingId).orElseThrow();
            if (posting.getStatus() == PostingStatus.APPLICATIONS_CLOSED) {
                posting.setStatus(PostingStatus.IN_PROGRESS);
            }
            posting.setStatus(PostingStatus.COMPLETED);
            postingRepository.save(posting);

            Report report = report(postingId, stillIn.keySet());
            notifier.driveEndedInProcess(postingId, stillIn.values());
            notifier.departmentReport(postingId, report.recipients);

            log.info("Drive ended: postingId={} actorId={} offered={} endedInProcess={} departments={} "
                            + "coordinators={} departmentsWithoutCoordinator={}",
                    postingId, actor.userId(), report.offered, stillIn.size(), report.departments,
                    report.recipients.size(), report.uncovered);
            return new EndDriveResult(report.offered, stillIn.size(), report.departments,
                    report.recipients.size(), report.uncovered);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not end postingId={} actorId={}: {}", postingId, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not end the drive. Nothing was changed; "
                    + "please try again.");
        }
    }

    private record Report(List<NotificationDispatchEvent.Recipient> recipients, int departments, int offered,
                          List<String> uncovered) {}

    /** A department: the key for grouping, and the label a coordinator reads. */
    private record Dept(long collegeId, long deptId, String college, String department) {}

    private Report report(Long postingId, Set<Long> endedInProcess) {
        // Everyone who reached the rounds: anyone with a round result, plus those
        // still in the drive when it ended (who may have none, if it ended before
        // round 1 was published). One query, with each student's furthest round.
        List<Long> ended = endedInProcess.isEmpty() ? List.of(-1L) : List.copyOf(endedInProcess);
        Map<Dept, List<Map<String, String>>> byDept = new LinkedHashMap<>();
        int[] offered = {0};
        jdbc.query("""
                SELECT a.id, a.status, u.first_name, u.last_name, u.email, sp.prn,
                       ura.college_id, ura.dept_id, c.name, d.name,
                       last.seq, last.name, last.outcome
                  FROM job_applications a
                  JOIN users u ON u.id = a.student_user_id
                  LEFT JOIN student_profiles sp ON sp.user_id = u.id
                  LEFT JOIN LATERAL (
                        SELECT x.college_id, x.dept_id FROM user_role_assignments x
                         WHERE x.user_id = u.id AND x.role = 'STUDENT' AND x.is_active = true
                         ORDER BY x.id LIMIT 1) ura ON true
                  LEFT JOIN colleges c ON c.id = ura.college_id
                  LEFT JOIN departments d ON d.id = ura.dept_id
                  LEFT JOIN LATERAL (
                        SELECT r.seq, r.name, rr.outcome FROM round_results rr
                          JOIN drive_rounds r ON r.id = rr.round_id
                         WHERE rr.application_id = a.id
                         ORDER BY r.seq DESC LIMIT 1) last ON true
                 WHERE a.posting_id = :p AND a.is_deleted = false
                   AND (a.id IN (:ended) OR EXISTS (SELECT 1 FROM round_results x WHERE x.application_id = a.id))
                """, new MapSqlParameterSource("p", postingId).addValue("ended", ended), rs -> {
            long collegeId = rs.getLong(7);
            boolean noCollege = rs.wasNull();
            long deptId = rs.getLong(8);
            boolean noDept = rs.wasNull();
            Dept dept = new Dept(noCollege ? -1 : collegeId, noDept ? -1 : deptId,
                    rs.getString(9) == null ? "No college" : rs.getString(9),
                    rs.getString(10) == null ? "No department" : rs.getString(10));
            String status = rs.getString(2);
            int seq = rs.getInt(11);
            boolean hasRound = !rs.wasNull();
            String roundLabel = hasRound ? "Round " + seq + ": " + rs.getString(12) : "Before round 1";
            String outcome;
            if (ApplicationStatus.OFFERED.name().equals(status)) {
                outcome = "Offered";
                offered[0]++;
            } else if (endedInProcess.contains(rs.getLong(1))) {
                outcome = hasRound ? "Advanced; the drive ended" : "Shortlisted; the drive ended";
            } else {
                outcome = RoundOutcome.valueOf(rs.getString(13)).label();
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("prn", rs.getString(6) == null ? "-" : rs.getString(6));
            row.put("name", RoundSupport.name(rs.getString(3), rs.getString(4)));
            row.put("email", rs.getString(5));
            row.put("department", dept.department());
            row.put("reached", roundLabel);
            row.put("outcome", outcome);
            byDept.computeIfAbsent(dept, k -> new ArrayList<>()).add(row);
        });
        byDept.values().forEach(rows -> rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("prn"))
                .thenComparing(r -> r.get("name"))));

        // Coordinators of those departments: active assignment, active account.
        Set<Long> colleges = new HashSet<>();
        byDept.keySet().forEach(d -> colleges.add(d.collegeId()));
        Map<Long, Object[]> people = new LinkedHashMap<>();        // userId -> [email, first, last]
        Map<Long, Set<Dept>> coordinates = new LinkedHashMap<>();  // userId -> their departments in this report
        Set<Dept> covered = new HashSet<>();
        if (!byDept.isEmpty()) {
            Map<String, Dept> byKey = new LinkedHashMap<>();
            byDept.keySet().forEach(d -> byKey.put(d.collegeId() + ":" + d.deptId(), d));
            jdbc.query("""
                    SELECT x.college_id, x.dept_id, u.id, u.email, u.first_name, u.last_name
                      FROM user_role_assignments x
                      JOIN users u ON u.id = x.user_id
                     WHERE x.role = 'FACULTY_DEPT_COORDINATOR'
                       AND x.is_active = true
                       AND u.status = 'ACTIVE'
                       AND u.is_deleted = false
                       AND x.college_id IN (:colleges)
                    """, new MapSqlParameterSource("colleges", colleges), rs -> {
                Dept d = byKey.get(rs.getLong(1) + ":" + rs.getLong(2));
                if (d == null) {
                    return;
                }
                long userId = rs.getLong(3);
                people.putIfAbsent(userId, new Object[]{rs.getString(4), rs.getString(5), rs.getString(6)});
                coordinates.computeIfAbsent(userId, k -> new TreeSet<>(Comparator.comparing(Dept::department)))
                        .add(d);
                covered.add(d);
            });
        }

        List<NotificationDispatchEvent.Recipient> recipients = new ArrayList<>();
        coordinates.forEach((userId, depts) -> {
            Object[] p = people.get(userId);
            String email = (String) p[0];
            if (email == null || email.isBlank()) {
                return;
            }
            List<Map<String, String>> rows = new ArrayList<>();
            int deptOffered = 0;
            for (Dept d : depts) {
                for (Map<String, String> r : byDept.get(d)) {
                    rows.add(r);
                    if ("Offered".equals(r.get("outcome"))) {
                        deptOffered++;
                    }
                }
            }
            String staffName = RoundSupport.name((String) p[1], (String) p[2]);
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("staffName", staffName);
            vars.put("departmentName", String.join(", ", depts.stream().map(Dept::department).toList()));
            vars.put("collegeName", String.join(", ", new TreeSet<>(depts.stream().map(Dept::college).toList())));
            vars.put("total", String.valueOf(rows.size()));
            vars.put("offered", String.valueOf(deptOffered));
            vars.put("rows", rows);
            recipients.add(new NotificationDispatchEvent.Recipient(userId, email, staffName, vars));
        });

        List<String> uncovered = byDept.keySet().stream().filter(d -> !covered.contains(d))
                .map(d -> d.college() + " - " + d.department() + " (" + byDept.get(d).size() + " students)")
                .toList();
        return new Report(recipients, byDept.size(), offered[0], uncovered);
    }
}

package org.yuktisetu.adminservice.recovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.service.EligibilityRunLauncher;
import org.yuktisetu.core.recovery.StartupRecovery;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Restarts eligibility resolutions a crash cut short.
 *
 * A resolution runs in one transaction on a background thread, so a crash rolls
 * all of its work back but leaves the run row RUNNING -- and a published drive
 * whose run never finishes has no eligible students and says nothing about why.
 * Re-running is safe: execute() rebuilds the drive's eligible set from scratch.
 *
 * Only runs started before this JVM did are touched (admin-service runs as a
 * single instance; a run started after boot belongs to this process). For a drive
 * with several abandoned runs, the newest is restarted and the rest closed.
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class EligibilityRunRecovery implements StartupRecovery {

    private final NamedParameterJdbcTemplate jdbc;
    private final EligibilityRunLauncher launcher;

    @Override
    public String name() {
        return "eligibility runs";
    }

    @Override
    @Transactional
    public int recover() {
        Instant jvmStart = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        // A drive that has moved past PUBLISHED (closed, running rounds, done) must
        // not have its eligible set rebuilt: close those runs instead.
        int closed = jdbc.update("""
                UPDATE eligibility_runs r SET status = 'FAILED', finished_at = now(), updated_at = now(),
                       error = 'Interrupted by a restart after the drive had moved on; not restarted.'
                  FROM job_postings p
                 WHERE p.id = r.posting_id AND r.status = 'RUNNING' AND r.started_at < :jvmStart
                   AND (p.is_deleted = true OR p.status <> 'PUBLISHED')
                """, new MapSqlParameterSource("jvmStart", Timestamp.from(jvmStart)));
        if (closed > 0) {
            log.warn("Closed {} eligibility run(s) interrupted by the previous shutdown on drives no longer published", closed);
        }

        record Orphan(long runId, long postingId, Long actor) {}
        List<Orphan> orphans = new ArrayList<>();
        jdbc.query("""
                SELECT r.id, r.posting_id, r.created_by FROM eligibility_runs r
                  JOIN job_postings p ON p.id = r.posting_id
                 WHERE r.status = 'RUNNING' AND r.started_at < :jvmStart
                   AND p.is_deleted = false AND p.status = 'PUBLISHED'
                 ORDER BY r.posting_id, r.started_at DESC
                """, new MapSqlParameterSource("jvmStart", Timestamp.from(jvmStart)), rs -> {
            long by = rs.getLong(3);
            orphans.add(new Orphan(rs.getLong(1), rs.getLong(2), rs.wasNull() ? null : by));
        });
        if (orphans.isEmpty()) {
            return closed;
        }
        Set<Long> seen = new HashSet<>();
        List<Long> superseded = new ArrayList<>();
        List<Orphan> restart = new ArrayList<>();
        for (Orphan o : orphans) {
            if (seen.add(o.postingId())) {
                restart.add(o);
            } else {
                superseded.add(o.runId());
            }
        }
        if (!superseded.isEmpty()) {
            jdbc.update("""
                    UPDATE eligibility_runs SET status = 'FAILED', finished_at = now(), updated_at = now(),
                           error = 'Interrupted by a restart; a newer run for this drive was restarted instead.'
                     WHERE id IN (:ids)
                    """, new MapSqlParameterSource("ids", superseded));
        }
        for (Orphan o : restart) {
            log.warn("Restarting eligibility run {} for postingId={} interrupted by the previous shutdown",
                    o.runId(), o.postingId());
            // Async: it runs after this transaction commits, on the heavy executor.
            launcher.launch(o.runId(), o.postingId(), o.actor());
        }
        return restart.size() + closed;
    }
}

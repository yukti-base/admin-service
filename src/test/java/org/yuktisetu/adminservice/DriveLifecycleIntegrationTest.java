package org.yuktisetu.adminservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.yuktisetu.adminservice.service.ApplicationRequalificationService;
import org.yuktisetu.adminservice.service.DriveNotificationService;
import org.yuktisetu.adminservice.service.JobPostingService;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.adminservice.service.PostingCancellationCascade;
import org.yuktisetu.core.notification.service.EmailService;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.identity.repository.UserRepository;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Database-backed cover for the two pieces of drive-lifecycle SQL that run inside
 * a transaction the unit of work owns: what cancelling a drive stops, and who a
 * re-publish disqualifies. Fixtures are a handful of rows, inserted directly.
 */
@SpringBootTest
@ActiveProfiles("test")
class DriveLifecycleIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository userRepository;
    @Autowired JobPostingService jobPostingService;
    @Autowired DriveNotificationService driveNotificationService;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired PostingCancellationCascade cascade;
    @Autowired ApplicationRequalificationService requalification;

    @MockitoBean EmailService emailService;

    private static final AtomicLong SEQ = new AtomicLong(700000);

    private long actorId;
    private long companyId;
    private long postingId;

    @BeforeEach
    void seed() {
        actorId = newUser("actor");
        jdbc.update("insert into companies (created_at, updated_at, is_deleted, code, name, status) "
                + "values (now(), now(), false, ?, 'Acme', 'ACTIVE')", "CO-" + UUID.randomUUID().toString().substring(0, 8));
        companyId = jdbc.queryForObject("select max(id) from companies", Long.class);
        postingId = newPosting();
    }

    private long newUser(String prefix) {
        long n = SEQ.incrementAndGet();
        Date now = new Date();
        User u = userRepository.save(User.builder()
                .email(prefix + n + "@example.test").phone("6" + String.format("%09d", n)).password("x")
                .firstName(prefix).lastName("T").status(UserStatus.ACTIVE)
                .createdAt(now).updatedAt(now).isDeleted(false).build());
        return u.getId();
    }

    private long newPosting() {
        jdbc.update("""
                insert into job_postings (created_at, updated_at, is_deleted, academic_year, application_deadline,
                       ctc_currency, job_type, marks_student_placed, notify_on_publish, package_basis, posting_code,
                       ppo_possible, shortlisting_mode, status, title, version, company_id)
                values (now(), now(), false, '2026-27', now() + interval '1 day', 'INR', 'FULL_TIME', false, false,
                        'CTC', ?, false, 'COMPOSITE_SCORE', 'PUBLISHED', 'SDE', 0, ?)
                """, "P-" + UUID.randomUUID().toString().substring(0, 8), companyId);
        return jdbc.queryForObject("select max(id) from job_postings", Long.class);
    }

    private long newRun(String status) {
        jdbc.update("insert into eligibility_runs (created_at, updated_at, is_deleted, run_reason, started_at, status, posting_id) "
                + "values (now(), now(), false, 'PUBLISH', now(), ?, ?)", status, postingId);
        return jdbc.queryForObject("select max(id) from eligibility_runs", Long.class);
    }

    private long newApplication(long studentId, String status) {
        jdbc.update("insert into job_applications (created_at, updated_at, is_deleted, applied_at, source, status, student_user_id, posting_id) "
                + "values (now(), now(), false, now(), 'EASY_APPLY', ?, ?, ?)", status, studentId, postingId);
        return jdbc.queryForObject("select max(id) from job_applications", Long.class);
    }

    private void makeEligible(long studentId, long runId) {
        makeEligible(studentId, runId, true);
    }

    private void makeEligible(long studentId, long runId, boolean notified) {
        jdbc.update("insert into posting_eligible_students (created_at, updated_at, is_deleted, evaluated_at, evaluation_run_id, notified, student_user_id, posting_id) "
                + "values (now(), now(), false, now(), ?, ?, ?, ?)", runId, notified, studentId, postingId);
    }

    private void outbox(String aggregateId) {
        jdbc.update("insert into outbox_events (attempts, available_at, created_at, event_id, event_type, payload, status, topic, updated_at, aggregate_type, aggregate_id) "
                + "values (0, now(), now(), ?, 'T', '{}'::jsonb, 'PENDING', 'topic', now(), 'JobPosting', ?)", UUID.randomUUID().toString(), aggregateId);
    }

    @Test
    @DisplayName("cancelling stops the running resolution, the open scoring gate and queued notifications -- for this drive only")
    void cancelStopsInFlightWork() {
        long running = newRun("RUNNING");
        long done = newRun("COMPLETED");
        jdbc.update("insert into drive_scoring_gates (posting_id, state, opened_at, pool_size, pending_count, captured_count, "
                + "failed_count, review_count, created_at, updated_at) values (?, 'COLLECTING', now(), 0, 0, 0, 0, 0, now(), now())", postingId);
        outbox(String.valueOf(postingId));
        outbox("999999999");   // some other drive's message

        PostingCancellationCascade.Result r = tx.execute(s -> cascade.stop(postingId, Instant.now(), actorId));

        assertEquals(1, r.runsStopped());
        assertEquals(1, r.gatesCancelled());
        assertEquals(1, r.notificationsDropped());
        assertEquals("FAILED", jdbc.queryForObject("select status from eligibility_runs where id = ?", String.class, running));
        assertEquals("COMPLETED", jdbc.queryForObject("select status from eligibility_runs where id = ?", String.class, done));
        assertEquals("CANCELLED", jdbc.queryForObject("select state from drive_scoring_gates where posting_id = ?", String.class, postingId));
        assertEquals("FAILED", jdbc.queryForObject("select status from outbox_events where aggregate_id = ?", String.class, String.valueOf(postingId)));
        assertEquals("PENDING", jdbc.queryForObject("select status from outbox_events where aggregate_id = '999999999'", String.class));
    }

    @Test
    @DisplayName("re-publish disqualifies live applicants who are no longer eligible, with audit history, and leaves the rest alone")
    void republishDisqualifiesOnlyTheIneligible() {
        long run = newRun("COMPLETED");
        long stillEligible = newUser("keep");
        long dropped = newUser("drop");
        long shortlisted = newUser("short");
        long withdrawn = newUser("withdrawn");
        makeEligible(stillEligible, run);

        long keepApp = newApplication(stillEligible, "APPLIED");
        long dropApp = newApplication(dropped, "APPLIED");
        long shortApp = newApplication(shortlisted, "SHORTLISTED");
        long withdrawnApp = newApplication(withdrawn, "WITHDRAWN");

        int n = tx.execute(s -> {
            return requalification.disqualifyNoLongerEligible(postingId, actorId);
        });

        assertEquals(2, n);
        assertEquals("APPLIED", statusOf(keepApp));
        assertEquals("DISQUALIFIED", statusOf(dropApp));
        assertEquals("DISQUALIFIED", statusOf(shortApp));
        assertEquals("WITHDRAWN", statusOf(withdrawnApp), "a finished application is not touched");

        assertEquals("The eligibility criteria for this drive were updated and your profile no longer meets them.",
                jdbc.queryForObject("select disqualification_reason from job_applications where id = ?", String.class, dropApp));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from application_status_history where application_id = ? and from_status = 'APPLIED' and to_status = 'DISQUALIFIED'",
                Integer.class, dropApp));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from application_status_history where application_id = ? and from_status = 'SHORTLISTED' and to_status = 'DISQUALIFIED'",
                Integer.class, shortApp));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from application_status_history where application_id = ?", Integer.class, keepApp));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ? and status = 'PENDING'",
                Integer.class, String.valueOf(postingId)), "one batched disqualification notice for both students");
    }

    private String statusOf(long applicationId) {
        return jdbc.queryForObject("select status from job_applications where id = ?", String.class, applicationId);
    }

    /**
     * The cancel that failed on 2026-10-19: its bulk UPDATEs clear the persistence
     * context, building the response then read the now-detached posting's lazy
     * Company proxy, and the LazyInitializationException rolled the whole cancel back
     * -- so the drive was never cancelled and the admin saw "Could not cancel".
     */
    @Test
    @DisplayName("cancelling a drive with applicants persists, retires the applications and answers with the company name")
    void cancelSurvivesTheClearedPersistenceContext() {
        long student = newUser("applicant");
        long app = newApplication(student, "APPLIED");
        UserPrincipal admin = new UserPrincipal(actorId, "admin@example.test",
                java.util.List.of(new UserPrincipal.RoleClaim("TNP_SUPER_ADMIN", null, null)));

        var response = jobPostingService.cancel(admin, postingId, "Company withdrew");

        assertEquals("CANCELLED", response.status().name());
        assertEquals("Acme", response.companyName(), "the lazy company must be readable after the bulk updates");
        assertEquals("CANCELLED", jdbc.queryForObject("select status from job_postings where id = ?", String.class, postingId),
                "the cancel must actually be committed, not rolled back");
        assertEquals("DRIVE_CANCELLED", statusOf(app));
    }

    /**
     * The notification that was lost on 2026-10-19: the eligibility transaction
     * detaches the posting (clearAutomatically), the notification then read its lazy
     * company, threw, and the swallow-everything catch logged and moved on. Handed an
     * ID, it re-reads the drive itself and cannot hit a detached proxy.
     */
    @Test
    @DisplayName("the drive-open blast is queued even after the persistence context has been cleared")
    void blastSurvivesAClearedPersistenceContext() {
        jdbc.update("update job_postings set notify_on_publish = true where id = ?", postingId);
        long run = newRun("COMPLETED");
        long student = newUser("eligible");
        makeEligible(student, run, false);

        tx.executeWithoutResult(s -> {
            entityManager.clear();            // what the @Modifying(clearAutomatically) statements do mid-run
            driveNotificationService.queueDrivePublishedBlast(postingId);
        });

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ? and status = 'PENDING'",
                Integer.class, String.valueOf(postingId)), "one dispatch message queued for the eligible student");
        assertTrue(jdbc.queryForObject(
                "select notified from posting_eligible_students where posting_id = ? and student_user_id = ?",
                Boolean.class, postingId, student), "the student is marked notified");
    }
}

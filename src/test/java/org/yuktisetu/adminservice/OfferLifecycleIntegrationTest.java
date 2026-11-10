package org.yuktisetu.adminservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.yuktisetu.adminservice.offers.DriveResultsController;
import org.yuktisetu.adminservice.offers.DriveResultsService;
import org.yuktisetu.adminservice.offers.OfferAdminService;
import org.yuktisetu.adminservice.offers.OfferDtos.DriveResults;
import org.yuktisetu.adminservice.offers.OfferDtos.ResultRow;
import org.yuktisetu.adminservice.recovery.EligibilityRunRecovery;
import org.yuktisetu.adminservice.recovery.OutboxStartupRecovery;
import org.yuktisetu.adminservice.service.StudentInviteService;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.notification.service.EmailService;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.core.security.UserPrincipal.RoleClaim;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * After the offer (decided 2026-11-01): the TnP decides a student's withdrawal
 * request, only an IT admin records a company withdrawing an offer, the results
 * page lists every shortlisted student with details, and the startup recoveries
 * pick up what a crash left. Not @Transactional; runs against yuktisetu_test.
 */
@SpringBootTest
@ActiveProfiles("test")
class OfferLifecycleIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired OfferAdminService offers;
    @Autowired DriveResultsService results;
    @Autowired DriveResultsController resultsController;
    @Autowired StudentInviteService invites;
    @Autowired EligibilityRunRecovery runRecovery;
    @Autowired OutboxStartupRecovery outboxRecovery;
    @MockitoBean EmailService emailService;

    private static final AtomicLong SEQ = new AtomicLong(960000);

    private long collegeId;
    private long companyId;
    private long postingId;
    private UserPrincipal admin;
    private UserPrincipal itAdmin;

    @BeforeEach
    void seed() {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("insert into trusts (created_at, updated_at, is_deleted, code, name, status) values (now(), now(), false, ?, 'PCET', 'ACTIVE')", "T" + tag);
        long trust = jdbc.queryForObject("select max(id) from trusts", Long.class);
        jdbc.update("insert into colleges (created_at, updated_at, is_deleted, code, name, primary_contact_email, primary_contact_name, status, trust_id) "
                + "values (now(), now(), false, ?, ?, 'c@x.test', 'C', 'ACTIVE', ?)", "C" + tag, "College " + tag, trust);
        collegeId = jdbc.queryForObject("select max(id) from colleges", Long.class);
        jdbc.update("insert into companies (created_at, updated_at, is_deleted, code, name, status) values (now(), now(), false, ?, 'Acme', 'ACTIVE')", "CO" + tag);
        companyId = jdbc.queryForObject("select max(id) from companies", Long.class);
        jdbc.update("""
                insert into job_postings (created_at, updated_at, is_deleted, academic_year, application_deadline,
                       ctc_currency, job_type, marks_student_placed, notify_on_publish, package_basis, posting_code,
                       ppo_possible, shortlisting_mode, status, title, version, company_id, ctc_max,
                       shortlist_published_at, reopen_count)
                values (now(), now(), false, '2027', now() - interval '1 day', 'INR', 'FULL_TIME', true, false,
                        'CTC', ?, false, 'MANUAL', 'IN_PROGRESS', 'SDE', 0, ?, 1200000, now(), 0)
                """, "P" + tag, companyId);
        postingId = jdbc.queryForObject("select max(id) from job_postings", Long.class);
        jdbc.update("insert into posting_targets (created_at, updated_at, is_deleted, batch_year, target_key, college_id, posting_id) values (now(), now(), false, 2027, ?, ?, ?)",
                "K" + tag, collegeId, postingId);
        admin = new UserPrincipal(user("admin"), "admin@x.test", List.of(new RoleClaim("TNP_SUPER_ADMIN", null, null)));
        itAdmin = new UserPrincipal(user("it"), "it@x.test", List.of(new RoleClaim("IT_ADMIN", null, null)));
    }

    private long user(String prefix) {
        long n = SEQ.incrementAndGet();
        jdbc.update("insert into users (email, phone, password, first_name, last_name, status, created_at, updated_at, is_deleted) "
                + "values (?, ?, 'x', ?, 'T', 'ACTIVE', now(), now(), false)", prefix + n + "@x.test", "8" + String.format("%09d", n), prefix + n);
        return jdbc.queryForObject("select id from users where email = ?", Long.class, prefix + n + "@x.test");
    }

    /** A student who reached the final round and holds an offer; returns {applicationId, offerId, studentId}. */
    private long[] offered(String appStatus, String offerStatus) {
        long student = user("stu");
        jdbc.update("insert into user_role_assignments (assigned_at, college_id, is_active, role, assigned_by, user_id) values (now(), ?, true, 'STUDENT', ?, ?)",
                collegeId, admin.userId(), student);
        jdbc.update("insert into student_profiles (user_id, prn, cgpa) values (?, ?, 8.4)", student, "PRN" + student);
        jdbc.update("insert into job_applications (created_at, updated_at, is_deleted, applied_at, source, status, student_user_id, posting_id, resume_url, resume_file_name) "
                + "values (now(), now(), false, now(), 'EASY_APPLY', ?, ?, ?, 'https://example.invalid/cv.pdf', 'cv.pdf')", appStatus, student, postingId);
        long app = jdbc.queryForObject("select max(id) from job_applications", Long.class);
        jdbc.update("insert into application_status_history (application_id, from_status, to_status, changed_at, changed_by, actor_kind, created_at, updated_at, is_deleted) "
                + "values (?, 'IN_REVIEW', 'SHORTLISTED', now(), ?, 'TNP', now(), now(), false)", app, admin.userId());
        jdbc.update("""
                insert into placement_offers (created_at, updated_at, is_deleted, counts_toward_placement, ctc_currency, ctc_amount,
                       offer_date, offer_type, package_basis, source, status, student_user_id, college_id, company_id,
                       posting_id, application_id)
                values (now(), now(), false, true, 'INR', 1200000, current_date, 'FULL_TIME', 'CTC', 'PORTAL', ?, ?, ?, ?, ?, ?)
                """, offerStatus, student, collegeId, companyId, postingId, app);
        long offer = jdbc.queryForObject("select max(id) from placement_offers", Long.class);
        return new long[]{app, offer, student};
    }

    private long pendingRequest(long offerId, long studentId) {
        jdbc.update("insert into offer_withdrawal_requests (offer_id, posting_id, student_user_id, reason, status, pending_lock, requested_at, version) "
                + "values (?, ?, ?, 'Joining higher studies abroad', 'PENDING', ?, now(), 0)", offerId, postingId, studentId, offerId);
        return jdbc.queryForObject("select max(id) from offer_withdrawal_requests", Long.class);
    }

    private String status(String table, long id) {
        return jdbc.queryForObject("select status from " + table + " where id = ?", String.class, id);
    }

    private int notices(String template) {
        return jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and payload->'payload'->>'templateKey' = ?",
                Integer.class, String.valueOf(postingId), template);
    }

    // ------------------------------------------------------------ withdrawal

    @Test
    @DisplayName("approving a withdrawal withdraws the offer, frees the student and tells them")
    void approveWithdrawal() {
        long[] o = offered("OFFER_ACCEPTED", "ACCEPTED");
        long req = pendingRequest(o[1], o[2]);

        offers.decideWithdrawal(admin, req, true, "Approved after a call");

        assertEquals("WITHDRAWN", status("placement_offers", o[1]));
        assertEquals("OFFER_DECLINED", status("job_applications", o[0]));
        assertEquals("APPROVED", status("offer_withdrawal_requests", req));
        assertNull(jdbc.queryForObject("select pending_lock from offer_withdrawal_requests where id = ?", Long.class, req),
                "a decided request releases the one-pending-per-offer lock");
        assertEquals("Joining higher studies abroad",
                jdbc.queryForObject("select withdrawal_reason from placement_offers where id = ?", String.class, o[1]));
        assertEquals(1, notices("offer-withdrawal-decided"));
    }

    @Test
    @DisplayName("refusing keeps the offer accepted, and a second decision is a conflict")
    void refuseThenDecideAgain() {
        long[] o = offered("OFFER_ACCEPTED", "ACCEPTED");
        long req = pendingRequest(o[1], o[2]);

        offers.decideWithdrawal(admin, req, false, null);
        assertEquals("ACCEPTED", status("placement_offers", o[1]));
        assertEquals("OFFER_ACCEPTED", status("job_applications", o[0]));

        ApiException e = assertThrows(ApiException.class, () -> offers.decideWithdrawal(admin, req, true, null));
        assertEquals("WITHDRAWAL_DECIDED", e.getCode());
    }

    // ------------------------------------------------- company withdrawal

    @Test
    @DisplayName("only an IT admin can record a company withdrawing an offer")
    void companyWithdrawalIsItAdminOnly() {
        long[] o = offered("OFFERED", "OFFERED");
        assertThrows(ApiException.class, () -> offers.withdrawForCompany(admin, o[1], "Hiring freeze at the company"));
        assertEquals("OFFERED", status("placement_offers", o[1]));
    }

    @Test
    @DisplayName("a company withdrawal of an accepted offer rejects the application and closes the student's request")
    void companyWithdrawsAcceptedOffer() {
        long[] o = offered("OFFER_ACCEPTED", "ACCEPTED");
        long req = pendingRequest(o[1], o[2]);

        offers.withdrawForCompany(itAdmin, o[1], "Hiring freeze at the company");

        assertEquals("REVOKED", status("placement_offers", o[1]));
        assertEquals("REJECTED", status("job_applications", o[0]));
        assertEquals("REJECTED", status("offer_withdrawal_requests", req));
        assertEquals(itAdmin.userId(), jdbc.queryForObject("select revoked_by from placement_offers where id = ?", Long.class, o[1]));
        assertEquals(1, notices("offer-withdrawn-by-company"));

        ApiException again = assertThrows(ApiException.class,
                () -> offers.withdrawForCompany(itAdmin, o[1], "Hiring freeze at the company"));
        assertEquals("OFFER_NOT_LIVE", again.getCode());
    }

    // --------------------------------------------------------------- results

    @Test
    @DisplayName("results list every shortlisted student with contact details, CGPA, resume and offer")
    void resultsCarryTheDetails() {
        long[] o = offered("OFFER_ACCEPTED", "ACCEPTED");
        pendingRequest(o[1], o[2]);

        DriveResults r = results.results(admin, postingId);

        assertEquals(1, r.shortlisted());
        assertEquals(1, r.pendingWithdrawals());
        ResultRow row = r.rows().get(0);
        assertEquals("PRN" + o[2], row.prn());
        assertNotNull(row.phone());
        assertEquals(0, new java.math.BigDecimal("8.4").compareTo(row.cgpa()));
        assertEquals("cv.pdf", row.resumeFileName());
        assertEquals("ACCEPTED", row.offer().status());
        assertEquals("PENDING", row.withdrawal().status());
        assertTrue(r.canDecideWithdrawals());
        assertFalse(r.canWithdrawForCompany(), "only IT admins see the company-withdrawal action");
    }

    @Test
    @DisplayName("a ground volunteer cannot open the results page (phone numbers and resumes)")
    void volunteerCannotReadResults() {
        var auth = new UsernamePasswordAuthenticationToken(
                new UserPrincipal(user("vol"), "v@x.test", List.of(new RoleClaim("GROUND_VOLUNTEER", collegeId, null))),
                null, List.of(new SimpleGrantedAuthority("ROLE_GROUND_VOLUNTEER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            assertThrows(AccessDeniedException.class, () -> resultsController.results(null, postingId));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // --------------------------------------------------------------- invites

    @Test
    @DisplayName("a ground volunteer can neither list nor re-send student invites")
    void volunteerCannotManageInvites() {
        UserPrincipal volunteer = new UserPrincipal(user("vol"), "v@x.test",
                List.of(new RoleClaim("GROUND_VOLUNTEER", collegeId, null)));
        assertThrows(ApiException.class, () -> invites.listPending(volunteer));
        assertThrows(ApiException.class, () -> invites.resend(volunteer, List.of(1L)));
    }

    // -------------------------------------------------------------- recovery

    @Test
    @DisplayName("an eligibility run left RUNNING on a published drive is restarted at startup")
    void orphanedEligibilityRunIsRestarted() throws Exception {
        jdbc.update("update job_postings set status = 'PUBLISHED' where id = ?", postingId);
        jdbc.update("insert into eligibility_runs (created_at, updated_at, is_deleted, run_reason, started_at, status, posting_id) "
                + "values (now(), now(), false, 'PUBLISH', now() - interval '1 hour', 'RUNNING', ?)", postingId);
        long run = jdbc.queryForObject("select max(id) from eligibility_runs", Long.class);

        assertTrue(runRecovery.recover() >= 1);

        // The restart runs on a background thread; it finishes one way or the other.
        String s = "RUNNING";
        for (int i = 0; i < 50 && "RUNNING".equals(s); i++) {
            Thread.sleep(100);
            s = status("eligibility_runs", run);
        }
        assertNotEquals("RUNNING", s, "the orphaned run must be picked up, not left RUNNING forever");
        String error = jdbc.queryForObject("select coalesce(error, '') from eligibility_runs where id = ?", String.class, run);
        assertFalse(error.contains("moved on"), "a published drive's run is restarted, not just closed");
    }

    @Test
    @DisplayName("an orphaned run on a drive that has moved on is closed, not re-run")
    void orphanedRunOnAMovedOnDriveIsClosed() {
        jdbc.update("insert into eligibility_runs (created_at, updated_at, is_deleted, run_reason, started_at, status, posting_id) "
                + "values (now(), now(), false, 'PUBLISH', now() - interval '1 hour', 'RUNNING', ?)", postingId);
        long run = jdbc.queryForObject("select max(id) from eligibility_runs", Long.class);

        runRecovery.recover();

        assertEquals("FAILED", status("eligibility_runs", run));
        assertTrue(jdbc.queryForObject("select error from eligibility_runs where id = ?", String.class, run).contains("moved on"));
    }

    @Test
    @DisplayName("startup re-queues outbox events given up during an outage, but not ones Kafka rejected")
    void outboxRecoveryRequeuesRetryableOnly() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        jdbc.update("insert into outbox_events (attempts, available_at, created_at, event_id, event_type, payload, status, topic, updated_at, last_error) "
                + "values (10, now(), now(), ?, 'T', '{}'::jsonb, 'FAILED', 'topic', now(), 'TimeoutException: broker down')", a);
        jdbc.update("insert into outbox_events (attempts, available_at, created_at, event_id, event_type, payload, status, topic, updated_at, last_error) "
                + "values (1, now(), now(), ?, 'T', '{}'::jsonb, 'FAILED', 'topic', now(), 'NON_RETRYABLE: RecordTooLargeException')", b);

        outboxRecovery.recover();

        assertEquals("PENDING", jdbc.queryForObject("select status from outbox_events where event_id = ?", String.class, a));
        assertEquals("FAILED", jdbc.queryForObject("select status from outbox_events where event_id = ?", String.class, b));
    }
}

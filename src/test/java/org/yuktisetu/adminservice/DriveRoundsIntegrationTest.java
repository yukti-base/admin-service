package org.yuktisetu.adminservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.rounds.DriveEndService;
import org.yuktisetu.adminservice.rounds.DriveRoundService;
import org.yuktisetu.adminservice.rounds.RoundDtos.CorrectionRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.EmailResolution;
import org.yuktisetu.adminservice.rounds.RoundDtos.EndDriveRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.EndDriveResult;
import org.yuktisetu.adminservice.rounds.RoundDtos.MarkItem;
import org.yuktisetu.adminservice.rounds.RoundDtos.MarkRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.MarkResult;
import org.yuktisetu.adminservice.rounds.RoundDtos.PublishRoundRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.PublishRoundResult;
import org.yuktisetu.adminservice.rounds.RoundDtos.RoundRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.RoundView;
import org.yuktisetu.adminservice.rounds.RoundMarkingService;
import org.yuktisetu.adminservice.rounds.RoundPublishingService;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.notification.service.EmailService;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.core.security.UserPrincipal.RoleClaim;
import org.yuktisetu.drive.model.OfferType;
import org.yuktisetu.drive.model.RoundMarkSource;
import org.yuktisetu.drive.model.RoundOutcome;
import org.yuktisetu.drive.model.RoundType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A drive's rounds against a real database: every edge case the 2026-10-30
 * design names, including two markers racing on the same students and a publish
 * racing the marks. Fixtures are a handful of rows, inserted directly.
 */
@SpringBootTest
@ActiveProfiles("test")
class DriveRoundsIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired DriveRoundService rounds;
    @Autowired RoundMarkingService marking;
    @Autowired RoundPublishingService publishing;
    @Autowired DriveEndService ending;

    @MockitoBean EmailService emailService;

    private static final AtomicLong SEQ = new AtomicLong(910000);

    private long collegeId;
    private long otherCollegeId;
    private long deptId;
    private long otherDeptId;
    private long postingId;
    private UserPrincipal admin;
    private UserPrincipal volunteer;

    @BeforeEach
    void seed() {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("insert into trusts (created_at, updated_at, is_deleted, code, name, status) values (now(), now(), false, ?, 'PCET', 'ACTIVE')", "T" + tag);
        long trust = jdbc.queryForObject("select max(id) from trusts", Long.class);
        collegeId = college(trust, "C" + tag);
        otherCollegeId = college(trust, "O" + tag);
        jdbc.update("insert into degrees (created_at, updated_at, is_deleted, code, name, status) values (now(), now(), false, ?, ?, 'ACTIVE')", "D" + tag, "B.Tech " + tag);
        long degree = jdbc.queryForObject("select max(id) from degrees", Long.class);
        deptId = dept(collegeId, degree, "CS" + tag, "Computer Engineering");
        otherDeptId = dept(collegeId, degree, "IT" + tag, "Information Technology");

        jdbc.update("insert into companies (created_at, updated_at, is_deleted, code, name, status) values (now(), now(), false, ?, 'Acme', 'ACTIVE')", "CO" + tag);
        long company = jdbc.queryForObject("select max(id) from companies", Long.class);
        jdbc.update("""
                insert into job_postings (created_at, updated_at, is_deleted, academic_year, application_deadline,
                       ctc_currency, job_type, marks_student_placed, notify_on_publish, package_basis, posting_code,
                       ppo_possible, shortlisting_mode, status, title, version, company_id, ctc_max,
                       shortlist_published_at, reopen_count)
                values (now(), now(), false, '2026-27', now() - interval '1 day', 'INR', 'INTERNSHIP', true, false,
                        'CTC', ?, false, 'MANUAL', 'APPLICATIONS_CLOSED', 'SDE Intern', 0, ?, 1200000, now(), 0)
                """, "P" + tag, company);
        postingId = jdbc.queryForObject("select max(id) from job_postings", Long.class);
        jdbc.update("insert into posting_targets (created_at, updated_at, is_deleted, batch_year, target_key, college_id, posting_id) values (now(), now(), false, 2027, ?, ?, ?)",
                "K" + tag, collegeId, postingId);

        long adminId = user("admin", "ACTIVE");
        admin = new UserPrincipal(adminId, "admin@x.test", List.of(new RoleClaim("TNP_SUPER_ADMIN", null, null)));
        long volunteerId = user("vol", "ACTIVE");
        volunteer = new UserPrincipal(volunteerId, "vol@x.test", List.of(new RoleClaim("GROUND_VOLUNTEER", collegeId, null)));
    }

    // ---------------------------------------------------------------- fixtures

    private long college(long trust, String code) {
        jdbc.update("insert into colleges (created_at, updated_at, is_deleted, code, name, primary_contact_email, primary_contact_name, status, trust_id) "
                + "values (now(), now(), false, ?, ?, 'c@x.test', 'C', 'ACTIVE', ?)", code, "College " + code, trust);
        return jdbc.queryForObject("select max(id) from colleges", Long.class);
    }

    private long dept(long college, long degree, String code, String name) {
        jdbc.update("insert into departments (created_at, updated_at, is_deleted, code, name, status, college_id, degree_id) "
                + "values (now(), now(), false, ?, ?, 'ACTIVE', ?, ?)", code, name, college, degree);
        return jdbc.queryForObject("select max(id) from departments", Long.class);
    }

    private long user(String prefix, String status) {
        long n = SEQ.incrementAndGet();
        jdbc.update("insert into users (email, phone, password, first_name, last_name, status, created_at, updated_at, is_deleted) "
                + "values (?, ?, 'x', ?, 'T', ?, now(), now(), false)", prefix + n + "@x.test", "7" + String.format("%09d", n), prefix + n, status);
        return jdbc.queryForObject("select id from users where email = ?", Long.class, prefix + n + "@x.test");
    }

    private void assign(long userId, String role, Long college, Long dept, boolean active) {
        jdbc.update("insert into user_role_assignments (assigned_at, college_id, dept_id, is_active, role, assigned_by, user_id) "
                + "values (now(), ?, ?, ?, ?, ?, ?)", college, dept, active, role, admin.userId(), userId);
    }

    /** A shortlisted student in the given department; returns the application id. */
    private long candidate(long dept) {
        long student = user("stu", "ACTIVE");
        assign(student, "STUDENT", collegeId, dept, true);
        jdbc.update("insert into student_profiles (user_id, prn) values (?, ?)", student, "PRN" + student);
        jdbc.update("insert into job_applications (created_at, updated_at, is_deleted, applied_at, source, status, student_user_id, posting_id) "
                + "values (now(), now(), false, now(), 'EASY_APPLY', 'SHORTLISTED', ?, ?)", student, postingId);
        return jdbc.queryForObject("select max(id) from job_applications", Long.class);
    }

    private List<Long> candidates(int n) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(candidate(deptId));
        return out;
    }

    private RoundView addRound(String name, RoundType type) {
        return rounds.add(admin, postingId, new RoundRequest(name, type, null, null, null, null, null, false));
    }

    private MarkResult mark(UserPrincipal who, long roundId, long app, RoundOutcome o, Integer expected) {
        return marking.mark(who, postingId, roundId, new MarkRequest(List.of(new MarkItem(app, o, expected)), RoundMarkSource.PORTAL));
    }

    private long marksVersion(long roundId) {
        return jdbc.queryForObject("select marks_version from drive_rounds where id = ?", Long.class, roundId);
    }

    private String status(long app) {
        return jdbc.queryForObject("select status from job_applications where id = ?", String.class, app);
    }

    private String code(Runnable r) {
        ApiException e = assertThrows(ApiException.class, r::run);
        return e.getCode();
    }

    private int outbox(String templateKey) {
        return jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? "
                + "and payload->'payload'->>'templateKey' = ?", Integer.class, String.valueOf(postingId), templateKey);
    }

    // ------------------------------------------------------------------- marks

    @Test
    @DisplayName("two markers on the same student: the second is told who marked what, never overwrites; the same value is no conflict")
    void concurrentMarkersOnOneStudent() {
        long app = candidates(1).get(0);
        long r1 = addRound("Aptitude", RoundType.APTITUDE_TEST).id();

        MarkResult first = mark(volunteer, r1, app, RoundOutcome.ADVANCED, null);
        assertEquals(1, first.applied());

        // Second marker also saw "unmarked" and chose ABSENT: conflict, not overwrite.
        MarkResult second = mark(admin, r1, app, RoundOutcome.ABSENT, null);
        assertEquals(0, second.applied());
        assertEquals(1, second.conflicts().size());
        assertEquals("CHANGED", second.conflicts().get(0).reason());
        assertEquals(RoundOutcome.ADVANCED, second.conflicts().get(0).currentOutcome());
        assertNotNull(second.conflicts().get(0).markedByName());
        assertEquals("ADVANCED", jdbc.queryForObject("select outcome from round_results where application_id = ?", String.class, app));

        // Same value as what is there: a no-op success.
        MarkResult same = mark(admin, r1, app, RoundOutcome.ADVANCED, null);
        assertEquals(1, same.unchanged());
        assertTrue(same.conflicts().isEmpty());

        // With the right version, the change applies and the version moves on.
        MarkResult change = mark(admin, r1, app, RoundOutcome.ABSENT, 0);
        assertEquals(1, change.applied());
        assertEquals(1, jdbc.queryForObject("select version from round_results where application_id = ?", Integer.class, app));
        assertEquals(2, jdbc.queryForObject("select count(*) from round_result_history where application_id = ?", Integer.class, app));
    }

    @Test
    @DisplayName("two markers racing over 40 students: every student ends with exactly one mark, and every lost race is reported")
    void racingMarkersLoseNothing() throws Exception {
        List<Long> apps = candidates(40);
        long r1 = addRound("Aptitude", RoundType.APTITUDE_TEST).id();
        List<MarkItem> allAdvanced = apps.stream().map(a -> new MarkItem(a, RoundOutcome.ADVANCED, null)).toList();
        List<MarkItem> allAbsent = apps.stream().map(a -> new MarkItem(a, RoundOutcome.ABSENT, null)).toList();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<MarkResult> a = pool.submit(() -> { go.await(); return marking.mark(volunteer, postingId, r1, new MarkRequest(allAdvanced, RoundMarkSource.PORTAL)); });
        Future<MarkResult> b = pool.submit(() -> { go.await(); return marking.mark(admin, postingId, r1, new MarkRequest(allAbsent, RoundMarkSource.EMAIL_LIST)); });
        go.countDown();
        MarkResult ra = a.get();
        MarkResult rb = b.get();
        pool.shutdown();

        assertEquals(40, ra.applied() + rb.applied(), "each student marked exactly once");
        assertEquals(40, ra.conflicts().size() + rb.conflicts().size(), "the loser hears about every student");
        assertEquals(40, jdbc.queryForObject("select count(*) from round_results where round_id = ?", Integer.class, r1));
        assertEquals(1, jdbc.queryForObject("select count(distinct outcome) from round_results where round_id = ?", Integer.class, r1),
                "one marker's whole batch won; the batches never interleave");
    }

    @Test
    @DisplayName("only the current round's candidates can be marked: not a disqualified student, not round 2 before round 1, not other colleges' volunteers")
    void markingBoundaries() {
        List<Long> apps = candidates(2);
        long r1 = addRound("Aptitude", RoundType.APTITUDE_TEST).id();
        long r2 = addRound("Interview", RoundType.TECHNICAL_INTERVIEW).id();

        jdbc.update("update job_applications set status = 'DISQUALIFIED' where id = ?", apps.get(1));
        MarkResult r = mark(volunteer, r1, apps.get(1), RoundOutcome.ADVANCED, null);
        assertEquals("NOT_A_CANDIDATE", r.conflicts().get(0).reason());
        assertEquals("DISQUALIFIED", r.conflicts().get(0).applicationStatus());

        assertEquals("NOT_CURRENT_ROUND", code(() -> mark(volunteer, r2, apps.get(0), RoundOutcome.ADVANCED, null)));

        UserPrincipal stranger = new UserPrincipal(user("x", "ACTIVE"), "x@x.test",
                List.of(new RoleClaim("GROUND_VOLUNTEER", otherCollegeId, null)));
        assertThrows(AdminExceptions.InsufficientAuthorityException.class,
                () -> mark(stranger, r1, apps.get(0), RoundOutcome.ADVANCED, null));

        // A volunteer marks, but cannot publish.
        assertThrows(AdminExceptions.InsufficientAuthorityException.class, () -> publishing.publish(volunteer, postingId, r1,
                new PublishRoundRequest(marksVersion(r1), false, true, null)));
    }

    @Test
    @DisplayName("rounds wait for the shortlist")
    void roundsWaitForShortlist() {
        long app = candidates(1).get(0);
        long r1 = addRound("Aptitude", RoundType.APTITUDE_TEST).id();
        jdbc.update("update job_postings set shortlist_published_at = null where id = ?", postingId);
        assertEquals("SHORTLIST_NOT_PUBLISHED", code(() -> mark(admin, r1, app, RoundOutcome.ADVANCED, null)));
    }

    @Test
    @DisplayName("a publish racing live marking: it either publishes exactly the marks it reviewed, or is refused -- never a mixture")
    void publishRacingMarks() throws Exception {
        List<Long> apps = candidates(30);
        long r1 = addRound("Aptitude", RoundType.APTITUDE_TEST).id();
        addRound("HR", RoundType.HR_INTERVIEW);
        long reviewed = marksVersion(r1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<Integer> marker = pool.submit(() -> {
            go.await();
            int applied = 0;
            for (Long app : apps) {
                try {
                    applied += mark(volunteer, r1, app, RoundOutcome.ADVANCED, null).applied();
                } catch (ApiException e) {
                    assertEquals("ROUND_PUBLISHED", e.getCode());
                }
            }
            return applied;
        });
        Future<String> publisher = pool.submit(() -> {
            go.await();
            try {
                publishing.publish(admin, postingId, r1, new PublishRoundRequest(reviewed, false, true, null));
                return "PUBLISHED";
            } catch (ApiException e) {
                return e.getCode();
            }
        });
        go.countDown();
        int applied = marker.get();
        String outcome = publisher.get();
        pool.shutdown();

        if ("PUBLISHED".equals(outcome)) {
            // It won before any mark: everyone was unmarked, so everyone is out, and no mark landed after.
            assertEquals(0, applied);
            assertEquals(30, jdbc.queryForObject("select count(*) from round_results where round_id = ? and source = 'PUBLISH_DEFAULT'", Integer.class, r1));
        } else {
            assertEquals("MARKS_CHANGED", outcome, "a mark got in first, so the reviewed version is stale");
            assertEquals("PLANNED", jdbc.queryForObject("select status from drive_rounds where id = ?", String.class, r1));
            assertEquals(30, applied);
        }
        // The invariant either way: no SHORTLISTED student holds a non-advancing published result.
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from round_results rr join drive_rounds r on r.id = rr.round_id
                  join job_applications a on a.id = rr.application_id
                 where r.id = ? and r.status = 'PUBLISHED' and rr.outcome <> 'ADVANCED' and a.status = 'SHORTLISTED'
                """, Integer.class, r1));
    }

    // ----------------------------------------------------------------- publish

    @Test
    @DisplayName("publishing: refused over changed marks, refused with unmarked until confirmed, then everyone not advanced is out")
    void publishRound() {
        List<Long> apps = candidates(4);
        long r1 = addRound("Aptitude", RoundType.APTITUDE_TEST).id();
        addRound("Interview", RoundType.TECHNICAL_INTERVIEW);
        mark(volunteer, r1, apps.get(0), RoundOutcome.ADVANCED, null);
        long reviewed = marksVersion(r1);
        mark(volunteer, r1, apps.get(1), RoundOutcome.ABSENT, null);   // after the coordinator looked

        assertEquals("MARKS_CHANGED", code(() -> publishing.publish(admin, postingId, r1,
                new PublishRoundRequest(reviewed, false, true, null))));
        assertEquals("UNMARKED_CANDIDATES", code(() -> publishing.publish(admin, postingId, r1,
                new PublishRoundRequest(marksVersion(r1), false, false, null))));
        assertEquals("NOT_LAST_ROUND", code(() -> publishing.publish(admin, postingId, r1,
                new PublishRoundRequest(marksVersion(r1), true, true, null))));

        PublishRoundResult res = publishing.publish(admin, postingId, r1, new PublishRoundRequest(marksVersion(r1), false, true, null));
        assertEquals(1, res.advanced());
        assertEquals(1, res.absent());
        assertEquals(2, res.notAdvanced());
        assertEquals(2, res.unmarkedCountedNotAdvanced());
        assertEquals("SHORTLISTED", status(apps.get(0)));
        assertEquals("REJECTED", status(apps.get(1)));
        assertEquals("REJECTED", status(apps.get(2)));
        assertEquals(2, jdbc.queryForObject("select count(*) from round_results where round_id = ? and source = 'PUBLISH_DEFAULT'", Integer.class, r1));
        assertEquals("IN_PROGRESS", jdbc.queryForObject("select status from job_postings where id = ?", String.class, postingId));
        assertTrue(jdbc.queryForObject("select count(*) from application_status_history where application_id = ? and reason like 'Absent in Round 1%'", Integer.class, apps.get(1)) == 1);
        assertEquals(3, outbox("round-result"), "one batch per outcome: advanced, not advanced, absent");

        // Published: no more marks; a second publish is refused.
        assertEquals("ROUND_PUBLISHED", code(() -> mark(volunteer, r1, apps.get(0), RoundOutcome.ABSENT, 0)));
        assertEquals("ROUND_PUBLISHED", code(() -> publishing.publish(admin, postingId, r1, new PublishRoundRequest(marksVersion(r1), false, true, null))));
        // Reopen-style edits of a published round are refused too.
        assertEquals("ROUND_PUBLISHED", code(() -> rounds.edit(admin, postingId, r1, new RoundRequest("X", null, null, null, null, null, null, false))));
    }

    @Test
    @DisplayName("a mark on a student disqualified after being marked is dropped at publish, with history")
    void staleMarksDropped() {
        List<Long> apps = candidates(2);
        long r1 = addRound("Aptitude", RoundType.APTITUDE_TEST).id();
        addRound("HR", RoundType.HR_INTERVIEW);
        mark(volunteer, r1, apps.get(0), RoundOutcome.ADVANCED, null);
        mark(volunteer, r1, apps.get(1), RoundOutcome.ADVANCED, null);
        jdbc.update("update job_applications set status = 'DISQUALIFIED' where id = ?", apps.get(1));

        PublishRoundResult res = publishing.publish(admin, postingId, r1, new PublishRoundRequest(marksVersion(r1), false, false, null));
        assertEquals(1, res.droppedNonCandidateMarks());
        assertEquals(1, res.advanced());
        assertEquals(0, jdbc.queryForObject("select count(*) from round_results where application_id = ?", Integer.class, apps.get(1)));
        assertEquals("DISQUALIFIED", status(apps.get(1)));
    }

    @Test
    @DisplayName("the final round: must be confirmed; advancing students are OFFERED with an offer record and congratulated")
    void finalRound() {
        List<Long> apps = candidates(2);
        long r1 = addRound("Interview", RoundType.TECHNICAL_INTERVIEW).id();
        mark(admin, r1, apps.get(0), RoundOutcome.ADVANCED, null);
        mark(admin, r1, apps.get(1), RoundOutcome.NOT_ADVANCED, null);

        assertEquals("CONFIRM_FINAL", code(() -> publishing.publish(admin, postingId, r1,
                new PublishRoundRequest(marksVersion(r1), false, false, null))));
        PublishRoundResult res = publishing.publish(admin, postingId, r1,
                new PublishRoundRequest(marksVersion(r1), true, false, OfferType.INTERNSHIP));
        assertEquals(1, res.offered());
        assertEquals("OFFERED", status(apps.get(0)));
        assertEquals("REJECTED", status(apps.get(1)));
        long student = jdbc.queryForObject("select student_user_id from job_applications where id = ?", Long.class, apps.get(0));
        assertEquals(student, jdbc.queryForObject("select active_internship_lock from placement_offers where application_id = ?", Long.class, apps.get(0)),
                "an internship offer takes the internship lock");
        assertEquals(1200000, jdbc.queryForObject("select ctc_amount from placement_offers where application_id = ?", Integer.class, apps.get(0)));
        assertEquals(1, outbox("drive-offered"));
        assertEquals("FINAL_ROUND_PUBLISHED", code(() -> addRound("Extra", RoundType.OTHER)));
    }

    @Test
    @DisplayName("an advancing student who already holds a live internship stops the final publish, naming them; nothing changes")
    void internshipLockStopsFinal() {
        List<Long> apps = candidates(2);
        long student = jdbc.queryForObject("select student_user_id from job_applications where id = ?", Long.class, apps.get(0));
        long company = jdbc.queryForObject("select company_id from job_postings where id = ?", Long.class, postingId);
        jdbc.update("""
                insert into placement_offers (created_at, updated_at, is_deleted, student_user_id, college_id, company_id,
                       offer_type, status, ctc_currency, package_basis, offer_date, counts_toward_placement, source, active_internship_lock)
                values (now(), now(), false, ?, ?, ?, 'INTERNSHIP', 'OFFERED', 'INR', 'CTC', current_date, true, 'TNP_MANUAL', ?)
                """, student, collegeId, company, student);
        long r1 = addRound("Interview", RoundType.TECHNICAL_INTERVIEW).id();
        mark(admin, r1, apps.get(0), RoundOutcome.ADVANCED, null);
        mark(admin, r1, apps.get(1), RoundOutcome.ADVANCED, null);

        assertEquals("CANNOT_RECORD_OFFER", code(() -> publishing.publish(admin, postingId, r1,
                new PublishRoundRequest(marksVersion(r1), true, false, OfferType.INTERNSHIP))));
        assertEquals("SHORTLISTED", status(apps.get(0)));
        assertEquals("SHORTLISTED", status(apps.get(1)));
        assertEquals("PLANNED", jdbc.queryForObject("select status from drive_rounds where id = ?", String.class, r1));
    }

    // -------------------------------------------------------------- correction

    @Test
    @DisplayName("correcting round 1: out of the drive clears their round-2 draft; back in rejoins; frozen once round 2 is out")
    void corrections() {
        List<Long> apps = candidates(3);
        long r1 = addRound("Aptitude", RoundType.APTITUDE_TEST).id();
        long r2 = addRound("Interview", RoundType.TECHNICAL_INTERVIEW).id();
        mark(admin, r1, apps.get(0), RoundOutcome.ADVANCED, null);
        mark(admin, r1, apps.get(1), RoundOutcome.ADVANCED, null);
        mark(admin, r1, apps.get(2), RoundOutcome.NOT_ADVANCED, null);
        publishing.publish(admin, postingId, r1, new PublishRoundRequest(marksVersion(r1), false, false, null));
        mark(volunteer, r2, apps.get(1), RoundOutcome.ADVANCED, null);   // draft in round 2
        long r2Before = marksVersion(r2);

        // A volunteer cannot correct; a reason is required by validation, a stale version is refused.
        assertThrows(AdminExceptions.InsufficientAuthorityException.class, () -> publishing.correct(volunteer, postingId, r1,
                new CorrectionRequest(apps.get(1), RoundOutcome.ABSENT, "Was not in the hall", 0)));
        assertEquals("CHANGED", code(() -> publishing.correct(admin, postingId, r1,
                new CorrectionRequest(apps.get(1), RoundOutcome.ABSENT, "Was not in the hall", 7))));

        publishing.correct(admin, postingId, r1, new CorrectionRequest(apps.get(1), RoundOutcome.ABSENT, "Was not in the hall", 0));
        assertEquals("REJECTED", status(apps.get(1)));
        assertEquals(0, jdbc.queryForObject("select count(*) from round_results where round_id = ? and application_id = ?", Integer.class, r2, apps.get(1)),
                "their draft mark in round 2 is gone");
        assertEquals(1, jdbc.queryForObject("select count(*) from round_result_history where round_id = ? and kind = 'CASCADE_CLEARED'", Integer.class, r2));
        assertTrue(marksVersion(r2) > r2Before, "a publish of round 2 reviewed before the correction is refused");

        int v = jdbc.queryForObject("select version from round_results where round_id = ? and application_id = ?", Integer.class, r1, apps.get(2));
        publishing.correct(admin, postingId, r1, new CorrectionRequest(apps.get(2), RoundOutcome.ADVANCED, "Marks were swapped", v));
        assertEquals("SHORTLISTED", status(apps.get(2)), "back in the drive, now a round-2 candidate");
        assertEquals(2, outbox("round-result-corrected"));

        publishing.publish(admin, postingId, r2, new PublishRoundRequest(marksVersion(r2), true, true, null));
        int v0 = jdbc.queryForObject("select version from round_results where round_id = ? and application_id = ?", Integer.class, r1, apps.get(0));
        assertEquals("ROUND_FROZEN", code(() -> publishing.correct(admin, postingId, r1,
                new CorrectionRequest(apps.get(0), RoundOutcome.ABSENT, "Too late now", v0))));
    }

    @Test
    @DisplayName("correcting the final round revokes the offer, or records one")
    void finalRoundCorrection() {
        List<Long> apps = candidates(2);
        long r1 = addRound("Interview", RoundType.TECHNICAL_INTERVIEW).id();
        mark(admin, r1, apps.get(0), RoundOutcome.ADVANCED, null);
        mark(admin, r1, apps.get(1), RoundOutcome.NOT_ADVANCED, null);
        publishing.publish(admin, postingId, r1, new PublishRoundRequest(marksVersion(r1), true, false, OfferType.INTERNSHIP));

        publishing.correct(admin, postingId, r1, new CorrectionRequest(apps.get(0), RoundOutcome.NOT_ADVANCED, "Wrong student ticked", 0));
        assertEquals("REJECTED", status(apps.get(0)));
        assertEquals("REVOKED", jdbc.queryForObject("select status from placement_offers where application_id = ?", String.class, apps.get(0)));
        assertNull(jdbc.queryForObject("select active_internship_lock from placement_offers where application_id = ?", Long.class, apps.get(0)),
                "the internship lock is released");

        publishing.correct(admin, postingId, r1, new CorrectionRequest(apps.get(1), RoundOutcome.ADVANCED, "This was the right student", 0));
        assertEquals("OFFERED", status(apps.get(1)));
        assertEquals("INTERNSHIP", jdbc.queryForObject("select offer_type from placement_offers where application_id = ? and status = 'OFFERED'", String.class, apps.get(1)));
    }

    // ------------------------------------------------------------- definition

    @Test
    @DisplayName("removing a planned round renumbers the rest; a round with marks cannot be removed")
    void removeRounds() {
        long app = candidates(1).get(0);
        long r1 = addRound("A", RoundType.APTITUDE_TEST).id();
        long r2 = addRound("B", RoundType.GROUP_DISCUSSION).id();
        long r3 = addRound("C", RoundType.HR_INTERVIEW).id();
        mark(admin, r1, app, RoundOutcome.ADVANCED, null);
        assertEquals("ROUND_HAS_MARKS", code(() -> rounds.delete(admin, postingId, r1)));
        rounds.delete(admin, postingId, r2);
        assertEquals(2, jdbc.queryForObject("select seq from drive_rounds where id = ?", Integer.class, r3));
    }

    @Test
    @DisplayName("an email list resolves into candidates, unknown accounts, non-applicants and students out of the round")
    void resolveEmails() {
        List<Long> apps = candidates(2);
        long r1 = addRound("A", RoundType.APTITUDE_TEST).id();
        String in = jdbc.queryForObject("select u.email from users u join job_applications a on a.student_user_id = u.id where a.id = ?", String.class, apps.get(0));
        String out = jdbc.queryForObject("select u.email from users u join job_applications a on a.student_user_id = u.id where a.id = ?", String.class, apps.get(1));
        jdbc.update("update job_applications set status = 'REJECTED' where id = ?", apps.get(1));
        long stranger = user("never", "ACTIVE");
        String strangerEmail = jdbc.queryForObject("select email from users where id = ?", String.class, stranger);

        EmailResolution r = marking.resolveEmails(volunteer, postingId, r1,
                "Name,Email\nA," + in.toUpperCase() + "\nB," + out + "\nC," + strangerEmail + "\nD,ghost@nowhere.test\n" + in + "\nbroken@x");
        assertEquals(1, r.matched().size());
        assertEquals(apps.get(0), r.matched().get(0).applicationId());
        assertEquals(List.of("ghost@nowhere.test"), r.noAccount());
        assertEquals(List.of(strangerEmail), r.notApplied());
        assertEquals("REJECTED", r.notInRound().get(0).applicationStatus());
        assertEquals(1, r.duplicates().get(in));
        assertFalse(r.unreadable().isEmpty());
    }

    // -------------------------------------------------------------- end drive

    @Test
    @DisplayName("ending: needs confirmation with students still in; each department's active coordinators get their own list")
    void endDrive() {
        long cs1 = candidate(deptId);
        long cs2 = candidate(deptId);
        long it1 = candidate(otherDeptId);
        long r1 = addRound("Aptitude", RoundType.APTITUDE_TEST).id();
        addRound("HR", RoundType.HR_INTERVIEW);
        mark(admin, r1, cs1, RoundOutcome.ADVANCED, null);
        mark(admin, r1, cs2, RoundOutcome.ABSENT, null);
        mark(admin, r1, it1, RoundOutcome.ADVANCED, null);
        publishing.publish(admin, postingId, r1, new PublishRoundRequest(marksVersion(r1), false, false, null));

        long csCoordinator = user("fcs", "ACTIVE");
        assign(csCoordinator, "FACULTY_DEPT_COORDINATOR", collegeId, deptId, true);
        long revoked = user("frv", "ACTIVE");
        assign(revoked, "FACULTY_DEPT_COORDINATOR", collegeId, deptId, false);   // not active: not told
        // Nobody coordinates IT: reported back, not silently skipped.

        assertEquals("UNFINISHED_CANDIDATES", code(() -> ending.end(admin, postingId, new EndDriveRequest(false))));
        EndDriveResult res = ending.end(admin, postingId, new EndDriveRequest(true));
        assertEquals(2, res.endedInProcess());
        assertEquals(1, res.coordinatorsNotified());
        assertEquals(2, res.departmentsReported());
        assertEquals(1, res.departmentsWithoutCoordinator().size());
        assertTrue(res.departmentsWithoutCoordinator().get(0).contains("Information Technology"));
        assertEquals("COMPLETED", jdbc.queryForObject("select status from job_postings where id = ?", String.class, postingId));
        assertEquals("REJECTED", status(cs1));
        String report = jdbc.queryForObject("select payload::text from outbox_events where aggregate_id = ? "
                        + "and payload->'payload'->>'templateKey' = 'drive-department-report'",
                String.class, String.valueOf(postingId));
        assertTrue(report.contains("PRN"), "rows carry the PRN");
        assertTrue(report.contains("Absent"));
        assertFalse(report.contains("Information Technology\""), "the CS coordinator is not sent IT's students");
        assertEquals("DRIVE_ENDED", code(() -> mark(admin, r1, cs1, RoundOutcome.ADVANCED, 0)));
    }
}

package org.yuktisetu.adminservice;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.people.PeopleDtos.*;
import org.yuktisetu.adminservice.people.PeopleSearchService;
import org.yuktisetu.core.exception.ForbiddenException;
import org.yuktisetu.core.notification.service.EmailService;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.core.security.UserPrincipal.RoleClaim;
import org.yuktisetu.drive.model.OfferType;
import org.yuktisetu.identity.model.RoleType;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.profile.model.CodingPlatform;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * People search (decided 2026-11-07): "everything below" within the viewer's
 * scope, ACTIVE by default, deleted for IT only, and every filter group.
 *
 * Fixture, one trust and two colleges, every seeded person surnamed with a
 * per-test tag so searches never see other tests' rows:
 * <pre>
 *   College A: Comp (B.Tech), Mech (B.Tech)      College B: Comp
 *   sA1  A/Comp 2027 cgpa 8.5, 0 backlogs, java+react, LeetCode, placed, resume, adv field 5
 *   sA2  A/Comp 2027 cgpa 6.0, 2 backlogs, offer pending
 *   sA3  A/Mech 2028 cgpa 7.5, role revoked
 *   sA4  A/Comp invited, never activated, no profile
 *   sA5  A/Mech 2028 cgpa 7.0
 *   sB1  B/Comp 2027 cgpa 9.0, plus a REVOKED A/Comp assignment
 *   sNoCgpa B/Comp 2027, CGPA not filled in
 *   sDel A/Comp soft-deleted
 *   staff: college admin A, coordinator A, HOD A/Comp, FDC A/Comp, GV A, super admin
 * </pre>
 * Not @Transactional; yuktisetu_test is create-drop.
 */
@SpringBootTest
@ActiveProfiles("test")
class PeopleSearchIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PeopleSearchService people;
    @Autowired EntityManagerFactory emf;
    @MockitoBean EmailService emailService;

    private static final AtomicLong SEQ = new AtomicLong(970000);

    private String tag;
    private long collegeA, collegeB, compA, mechA, compB, degree, company, fieldId;
    private long sA1, sA2, sA3, sA4, sA5, sB1, sDel, sNoCgpa;
    private long cadminA, coordA, hodA, fdcA, gvA, superAdmin;

    // ------------------------------------------------------------ fixture

    @BeforeEach
    void seed() {
        tag = "zq" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("insert into trusts (created_at, updated_at, is_deleted, code, name, status) values (now(), now(), false, ?, 'PCET', 'ACTIVE')", "T" + tag);
        long trust = jdbc.queryForObject("select max(id) from trusts", Long.class);
        collegeA = college(trust, "A");
        collegeB = college(trust, "B");
        jdbc.update("insert into degrees (created_at, updated_at, is_deleted, code, name, status) values (now(), now(), false, ?, ?, 'ACTIVE')", "D" + tag, "BTech " + tag);
        degree = jdbc.queryForObject("select max(id) from degrees", Long.class);
        compA = dept(collegeA, "COMP");
        mechA = dept(collegeA, "MECH");
        compB = dept(collegeB, "COMP");
        jdbc.update("insert into companies (created_at, updated_at, is_deleted, code, name, status) values (now(), now(), false, ?, 'Acme', 'ACTIVE')", "CO" + tag);
        company = jdbc.queryForObject("select max(id) from companies", Long.class);

        superAdmin = staff("super", RoleType.TNP_SUPER_ADMIN, null, null);
        cadminA = staff("cadmin", RoleType.TNP_COLLEGE_ADMIN, collegeA, null);
        coordA = staff("coord", RoleType.TNP_COORDINATOR, collegeA, null);
        hodA = staff("hod", RoleType.HOD, collegeA, compA);
        fdcA = staff("fdc", RoleType.FACULTY_DEPT_COORDINATOR, collegeA, compA);
        gvA = staff("gv", RoleType.GROUND_VOLUNTEER, collegeA, null);

        sA1 = student("ACTIVE", collegeA, compA, true, 8.5, 2027, 0);
        sA2 = student("ACTIVE", collegeA, compA, true, 6.0, 2027, 2);
        sA3 = student("ACTIVE", collegeA, mechA, false, 7.5, 2028, 0);
        sA4 = user("stu", "PENDING_ACTIVATION");
        assign(sA4, RoleType.STUDENT, collegeA, compA, true);
        sA5 = student("ACTIVE", collegeA, mechA, true, 7.0, 2028, null);
        sNoCgpa = student("ACTIVE", collegeB, compB, true, null, 2027, null);
        sB1 = student("ACTIVE", collegeB, compB, true, 9.0, 2027, null);
        assign(sB1, RoleType.STUDENT, collegeA, compA, false);
        sDel = student("ACTIVE", collegeA, compA, true, 8.0, 2027, 0);
        jdbc.update("update users set is_deleted = true, deleted_at = now() where id = ?", sDel);

        long p1 = profile(sA1);
        jdbc.update("insert into student_skills (student_profile_id, skill) values (?, 'Java'), (?, 'React')", p1, p1);
        jdbc.update("insert into student_coding_profiles (student_profile_id, platform, username) values (?, 'LEETCODE', 'riya')", p1);
        jdbc.update("insert into student_documents (student_user_id, doc_type, file_name, storage_key, url, is_primary, uploaded_at, is_deleted) "
                + "values (?, 'RESUME', 'cv.pdf', 'k', 'https://example.invalid/cv.pdf', true, now(), false)", sA1);
        offer(sA1, "ACCEPTED");
        offer(sA2, "OFFERED");
        jdbc.update("insert into dynamic_field_definitions (field_key, label, data_type, is_active, created_by_user_id, created_at) values (?, 'Hackathons', 'NUMBER', true, ?, now())",
                "hk" + tag, superAdmin);
        fieldId = jdbc.queryForObject("select max(id) from dynamic_field_definitions", Long.class);
        jdbc.update("insert into student_dynamic_field_values (student_profile_id, field_definition_id, value_number) values (?, ?, 5)", p1, fieldId);
    }

    private long college(long trust, String code) {
        jdbc.update("insert into colleges (created_at, updated_at, is_deleted, code, name, primary_contact_email, primary_contact_name, status, trust_id) "
                + "values (now(), now(), false, ?, ?, 'c@x.test', 'C', 'ACTIVE', ?)", code + tag, "College " + code + tag, trust);
        return jdbc.queryForObject("select max(id) from colleges", Long.class);
    }

    private long dept(long college, String code) {
        jdbc.update("insert into departments (created_at, updated_at, is_deleted, code, name, status, college_id, degree_id) "
                + "values (now(), now(), false, ?, ?, 'ACTIVE', ?, ?)", code, code + " " + tag, college, degree);
        return jdbc.queryForObject("select max(id) from departments", Long.class);
    }

    private long user(String prefix, String status) {
        long n = SEQ.incrementAndGet();
        String email = prefix + n + "@x.test";
        jdbc.update("insert into users (email, phone, password, first_name, last_name, status, created_at, updated_at, is_deleted) "
                + "values (?, ?, 'x', ?, ?, ?, now(), now(), false)", email, "7" + String.format("%09d", n), prefix + n, tag, status);
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private void assign(long user, RoleType role, Long college, Long dept, boolean active) {
        jdbc.update("insert into user_role_assignments (assigned_at, college_id, dept_id, degree_id, is_active, role, assigned_by, user_id, revoked_at) "
                        + "values (now(), ?, ?, ?, ?, ?, ?, ?, ?)",
                college, dept, role == RoleType.STUDENT ? degree : null, active, role.name(),
                superAdmin == 0 ? user : superAdmin, user, active ? null : new java.sql.Timestamp(System.currentTimeMillis()));
    }

    private long staff(String prefix, RoleType role, Long college, Long dept) {
        long id = user(prefix, "ACTIVE");
        assign(id, role, college, dept, true);
        return id;
    }

    private long student(String status, long college, long dept, boolean active, Double cgpa, int batch, Integer backlogs) {
        long id = user("stu", status);
        assign(id, RoleType.STUDENT, college, dept, active);
        jdbc.update("insert into student_profiles (user_id, prn, cgpa, graduation_year, active_backlogs) values (?, ?, ?, ?, ?)",
                id, "PRN" + tag + id, cgpa, batch, backlogs);
        return id;
    }

    private long profile(long user) {
        return jdbc.queryForObject("select id from student_profiles where user_id = ?", Long.class, user);
    }

    private void offer(long student, String status) {
        jdbc.update("""
                insert into placement_offers (created_at, updated_at, is_deleted, counts_toward_placement, ctc_currency, ctc_amount,
                       offer_date, offer_type, package_basis, source, status, student_user_id, college_id, company_id)
                values (now(), now(), false, true, 'INR', 1200000, current_date, 'FULL_TIME', 'CTC', 'TNP_MANUAL', ?, ?, ?, ?)
                """, status, student, collegeA, company);
    }

    // ------------------------------------------------------------ principals and helpers

    private static UserPrincipal as(long id, RoleClaim... claims) {
        return new UserPrincipal(id, "p@x.test", List.of(claims));
    }

    private UserPrincipal superP() { return as(superAdmin, new RoleClaim("TNP_SUPER_ADMIN", null, null)); }
    private UserPrincipal itP() { return as(1L, new RoleClaim("IT_ADMIN", null, null)); }
    private UserPrincipal cadminP() { return as(cadminA, new RoleClaim("TNP_COLLEGE_ADMIN", collegeA, null)); }
    private UserPrincipal coordP() { return as(coordA, new RoleClaim("TNP_COORDINATOR", collegeA, null)); }
    private UserPrincipal hodP() { return as(hodA, new RoleClaim("HOD", collegeA, compA)); }
    private UserPrincipal fdcP() { return as(fdcA, new RoleClaim("FACULTY_DEPT_COORDINATOR", collegeA, compA)); }
    private UserPrincipal gvP() { return as(gvA, new RoleClaim("GROUND_VOLUNTEER", collegeA, null)); }

    /** Criteria with only the tag search set; tweak through the builder methods below. */
    private Students s() { return new Students(tag); }

    private Set<Long> students(UserPrincipal p, Students c) {
        return people.searchStudents(p, c.build(), 0, 100, null, null).content().stream()
                .map(StudentRow::userId).collect(Collectors.toSet());
    }

    private Set<Long> staff(UserPrincipal p, StaffSearchCriteria c) {
        return people.searchStaff(p, c, 0, 100, null, null).content().stream()
                .map(StaffRow::userId).collect(Collectors.toSet());
    }

    private StaffSearchCriteria staffQ() {
        return new StaffSearchCriteria(tag, null, null, null, null, null, null, null, null, null, null);
    }

    private static Set<Long> ids(long... ids) {
        return Arrays.stream(ids).boxed().collect(Collectors.toSet());
    }

    // ------------------------------------------------------------ visibility

    @Test
    @DisplayName("each role finds exactly everything below it, in its own scope")
    void visibilityPerRole() {
        assertEquals(ids(sA1, sA2, sA5, sB1, sNoCgpa), students(superP(), s()));
        assertEquals(ids(sA1, sA2, sA5), students(cadminP(), s()));
        assertEquals(ids(sA1, sA2, sA5), students(coordP(), s()));
        assertEquals(ids(sA1, sA2), students(hodP(), s()));
        assertEquals(ids(sA1, sA2), students(fdcP(), s()));

        assertEquals(ids(superAdmin, cadminA, coordA, hodA, fdcA, gvA), staff(superP(), staffQ()));
        assertEquals(ids(coordA, hodA, fdcA, gvA), staff(cadminP(), staffQ()));
        assertEquals(ids(hodA, fdcA, gvA), staff(coordP(), staffQ()));
        assertEquals(ids(fdcA), staff(hodP(), staffQ()));
        assertEquals(Set.of(), staff(fdcP(), staffQ()));

        assertThrows(ForbiddenException.class, () -> students(gvP(), s()));
        assertThrows(ForbiddenException.class, () -> people.filterOptions(gvP()));
    }

    @Test
    @DisplayName("a person with two roles sees the union of both scopes")
    void multiRoleUnion() {
        UserPrincipal both = as(9L, new RoleClaim("HOD", collegeA, mechA), new RoleClaim("FACULTY_DEPT_COORDINATOR", collegeA, compA));
        assertEquals(ids(sA1, sA2, sA5), students(both, s()));
    }

    @Test
    @DisplayName("college filter and status bind to the same assignment row")
    void filtersBindToOneAssignment() {
        // sB1 is active in B and revoked in A: "college A, active" must not match it.
        assertEquals(ids(sA1, sA2, sA5), students(itP(), s().colleges(collegeA)));
        // From college A's side sB1 is a deactivated student, as is sA3.
        assertEquals(ids(sA3, sB1), students(cadminP(), s().status(PersonStatus.DEACTIVATED)));
    }

    @Test
    @DisplayName("pending invites and deleted people: status filter, deleted for IT only")
    void statusAndDeleted() {
        assertEquals(ids(sA4), students(cadminP(), s().status(PersonStatus.PENDING_INVITE)));
        assertThrows(ForbiddenException.class, () -> students(superP(), s().status(PersonStatus.ANY).includeDeleted()));
        assertTrue(students(itP(), s().status(PersonStatus.ANY).includeDeleted()).contains(sDel));
        assertFalse(students(itP(), s().status(PersonStatus.ANY)).contains(sDel));
        assertEquals(ids(sA4), students(superP(), s().status(PersonStatus.ANY).accountStatuses(UserStatus.PENDING_ACTIVATION)));
    }

    // ------------------------------------------------------------ filters

    @Test
    @DisplayName("academic, skill, coding, document, placement and advanced-field filters")
    void filters() {
        UserPrincipal p = superP();
        assertEquals(ids(sA1, sB1), students(p, s().cgpaMin(8.0)));
        assertEquals(ids(sA1), students(p, s().backlogsMax(0)));       // null backlogs never match
        assertEquals(ids(sA5), students(p, s().batches(2028)));           // sA3 is revoked
        assertEquals(ids(sA1), students(p, s().skills(SkillMatch.ANY, "JAVA")));
        assertEquals(Set.of(), students(p, s().skills(SkillMatch.ALL, "java", "python")));
        assertEquals(Set.of(), students(p, s().skills(SkillMatch.ANY, "jav")));   // tags match whole
        assertEquals(ids(sA1), students(p, s().coding(CodingPlatform.LEETCODE)));
        assertEquals(ids(sA1), students(p, s().resume(true)));
        assertEquals(ids(sA1), students(p, s().placement(PlacementFilter.PLACED)));
        assertEquals(ids(sA2), students(p, s().placement(PlacementFilter.OFFER_PENDING)));
        assertEquals(ids(sA5, sB1, sNoCgpa), students(p, s().placement(PlacementFilter.UNPLACED)));
        assertEquals(ids(sA1, sA2), students(p, s().offerCountMin(1)));
        assertEquals(ids(sA1, sA2), students(p, s().offerTypes(OfferType.FULL_TIME)));
        assertEquals(ids(sA1), students(p, s().dynamic(fieldId + "|GTE|4")));
        assertEquals(Set.of(), students(p, s().dynamic(fieldId + "|GTE|6")));
        assertEquals(ids(sA1, sA2, sA5), students(p, s().departments(compA, mechA)));
        assertEquals(ids(sA1, sA2, sA5, sB1, sNoCgpa), students(p, s().degrees(degree)));

        // Every word must match: the tag (surname) and sA1's PRN.
        assertEquals(ids(sA1), students(p, new Students(tag + " prn" + tag + sA1)));

        assertThrows(AdminExceptions.InvalidRequestException.class, () -> students(p, s().dynamic(fieldId + "|CONTAINS|4")));
        assertThrows(AdminExceptions.InvalidRequestException.class, () -> students(p, s().dynamic("999999|EQ|4")));
    }

    @Test
    @DisplayName("staff filters: role, college, never logged in")
    void staffFilters() {
        assertEquals(ids(hodA), staff(superP(), new StaffSearchCriteria(tag, List.of(RoleType.HOD), null, null, null, null, null, null, null, null, null)));
        assertEquals(ids(hodA, fdcA), staff(superP(), new StaffSearchCriteria(tag, null, null, List.of(compA), null, null, null, null, null, null, null)));
        assertEquals(ids(superAdmin, cadminA, coordA, hodA, fdcA, gvA),
                staff(superP(), new StaffSearchCriteria(tag, null, null, null, null, null, null, true, null, null, null)));
        assertThrows(AdminExceptions.InvalidRequestException.class, () ->
                staff(superP(), new StaffSearchCriteria(tag, List.of(RoleType.STUDENT), null, null, null, null, null, null, null, null, null)));
    }

    // ------------------------------------------------------------ sort, paging, query count

    @Test
    @DisplayName("sort by CGPA pages stably, unfilled CGPA last either way; unknown sort keys are refused")
    void sortAndPaging() {
        PeoplePage<StudentRow> first = people.searchStudents(superP(), s().build(), 0, 2, "cgpa", "desc");
        PeoplePage<StudentRow> second = people.searchStudents(superP(), s().build(), 1, 2, "cgpa", "desc");
        PeoplePage<StudentRow> third = people.searchStudents(superP(), s().build(), 2, 2, "cgpa", "desc");
        assertEquals(List.of(sB1, sA1), first.content().stream().map(StudentRow::userId).toList());
        assertEquals(List.of(sA5, sA2), second.content().stream().map(StudentRow::userId).toList());
        assertEquals(List.of(sNoCgpa), third.content().stream().map(StudentRow::userId).toList());
        assertEquals(5, first.totalElements());
        assertFalse(second.last());
        assertTrue(third.last());
        List<Long> ascending = people.searchStudents(superP(), s().build(), 0, 10, "cgpa", "asc").content().stream()
                .map(StudentRow::userId).toList();
        assertEquals(List.of(sA2, sA5, sA1, sB1, sNoCgpa), ascending);

        StudentRow riya = first.content().get(1);
        assertEquals(PlacementFilter.PLACED, riya.placement());
        assertTrue(riya.hasPrimaryResume());
        assertEquals("Acme", riya.latestCompany());

        assertThrows(AdminExceptions.InvalidRequestException.class,
                () -> people.searchStudents(superP(), s().build(), 0, 10, "password", null));
        assertThrows(AdminExceptions.InvalidRequestException.class,
                () -> people.searchStaff(superP(), staffQ(), 0, 10, "cgpa", null));
    }

    @Test
    @DisplayName("a page costs the same number of statements whatever its size")
    void noQueryPerRow() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            people.searchStudents(superP(), s().build(), 0, 1, null, null);
            long one = stats.getPrepareStatementCount();
            assertTrue(one >= 2, "statistics must be recording (select + count at least), got " + one);
            stats.clear();
            people.searchStudents(superP(), s().build(), 0, 4, null, null);
            long four = stats.getPrepareStatementCount();
            assertEquals(one, four, "a 4-row page must not cost more statements than a 1-row page");

            stats.clear();
            people.searchStaff(superP(), staffQ(), 0, 1, null, null);
            long staffOne = stats.getPrepareStatementCount();
            stats.clear();
            people.searchStaff(superP(), staffQ(), 0, 6, null, null);
            assertEquals(staffOne, stats.getPrepareStatementCount());
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    // ------------------------------------------------------------ detail and options

    @Test
    @DisplayName("detail: full record in scope, 404 outside it, deleted for IT only")
    void detail() {
        PersonDetail d = people.detail(hodP(), sA1);
        assertEquals(Set.of("Java", "React"), Set.copyOf(d.student().skills()));
        assertEquals("riya", d.student().codingProfiles().get(0).username());
        assertEquals(1, d.student().offers().size());
        assertEquals(1, d.student().advancedFields().size());

        assertThrows(AdminExceptions.NotFoundException.class, () -> people.detail(hodP(), sA5));  // Mech, not Comp
        assertThrows(AdminExceptions.NotFoundException.class, () -> people.detail(hodP(), coordA)); // above the HOD
        assertThrows(AdminExceptions.NotFoundException.class, () -> people.detail(superP(), sDel));
        assertTrue(people.detail(itP(), sDel).account().deleted());
        assertNull(people.detail(superP(), sA4).student().prn()); // invited, no profile yet
        assertNull(people.detail(cadminP(), coordA).student());
    }

    @Test
    @DisplayName("filter options are scoped and lock a single-department viewer")
    void filterOptions() {
        PeopleFilterOptions hod = people.filterOptions(hodP());
        assertEquals(collegeA, hod.lockedCollegeId());
        assertEquals(compA, hod.lockedDepartmentId());
        assertEquals(1, hod.colleges().size());
        assertEquals(List.of(compA), hod.colleges().get(0).departments().stream().map(DepartmentOption::id).toList());
        assertTrue(hod.canSearchStaff());
        assertFalse(hod.canIncludeDeleted());

        PeopleFilterOptions fdc = people.filterOptions(fdcP());
        assertTrue(fdc.canSearchStudents());
        assertFalse(fdc.canSearchStaff());

        PeopleFilterOptions cadmin = people.filterOptions(cadminP());
        assertEquals(collegeA, cadmin.lockedCollegeId());
        assertNull(cadmin.lockedDepartmentId());
        assertEquals(2, cadmin.colleges().get(0).departments().size());

        PeopleFilterOptions it = people.filterOptions(itP());
        assertTrue(it.canIncludeDeleted());
        assertNull(it.lockedCollegeId());
        assertTrue(it.colleges().stream().anyMatch(c -> c.id() == collegeB));
        assertTrue(it.skillSuggestions().contains("java"));
        assertTrue(it.dynamicFields().stream().anyMatch(f -> f.id() == fieldId));
    }

    // ------------------------------------------------------------ criteria builder

    /** Mutable test-side builder for the 40-field criteria record. */
    private static final class Students {
        String q;
        List<Long> colleges, departments, degrees;
        List<Integer> batches;
        PersonStatus status;
        List<UserStatus> accountStatuses;
        Boolean includeDeleted, resume;
        Double cgpaMin;
        Integer backlogsMax, offerCountMin;
        List<String> skills;
        SkillMatch skillMatch;
        List<CodingPlatform> coding;
        PlacementFilter placement;
        List<OfferType> offerTypes;
        List<String> dynamic;

        Students(String q) { this.q = q; }
        Students colleges(Long... v) { colleges = List.of(v); return this; }
        Students departments(Long... v) { departments = List.of(v); return this; }
        Students degrees(Long... v) { degrees = List.of(v); return this; }
        Students batches(Integer... v) { batches = List.of(v); return this; }
        Students status(PersonStatus v) { status = v; return this; }
        Students accountStatuses(UserStatus... v) { accountStatuses = List.of(v); return this; }
        Students includeDeleted() { includeDeleted = true; return this; }
        Students cgpaMin(double v) { cgpaMin = v; return this; }
        Students backlogsMax(int v) { backlogsMax = v; return this; }
        Students skills(SkillMatch m, String... v) { skillMatch = m; skills = List.of(v); return this; }
        Students coding(CodingPlatform... v) { coding = List.of(v); return this; }
        Students resume(boolean v) { resume = v; return this; }
        Students placement(PlacementFilter v) { placement = v; return this; }
        Students offerCountMin(int v) { offerCountMin = v; return this; }
        Students offerTypes(OfferType... v) { offerTypes = List.of(v); return this; }
        Students dynamic(String... v) { dynamic = List.of(v); return this; }

        StudentSearchCriteria build() {
            return new StudentSearchCriteria(q, colleges, departments, degrees, batches, status, accountStatuses,
                    includeDeleted, null, null, null,
                    cgpaMin, null, null, null, null, null, null, null, backlogsMax, null, null, null,
                    skills, skillMatch, coding, null, null, null, null, resume, null,
                    placement, offerCountMin, null, offerTypes, dynamic);
        }
    }
}

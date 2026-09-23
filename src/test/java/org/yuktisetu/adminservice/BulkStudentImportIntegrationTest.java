package org.yuktisetu.adminservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.yuktisetu.adminservice.dto.BulkStudentRequest;
import org.yuktisetu.adminservice.dto.BulkStudentCreateResponse;
import org.yuktisetu.adminservice.dto.StudentRequest;
import org.yuktisetu.adminservice.service.BulkStudentService;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.db.UserRoleAssignment;
import org.yuktisetu.identity.model.RoleType;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.identity.repository.UserRepository;
import org.yuktisetu.identity.repository.UserRoleAssignmentRepository;
import org.yuktisetu.institute.db.College;
import org.yuktisetu.institute.db.Department;
import org.yuktisetu.institute.db.Trust;
import org.yuktisetu.institute.model.TenantStatus;
import org.yuktisetu.institute.repository.CollegeRepository;
import org.yuktisetu.institute.repository.DepartmentRepository;
import org.yuktisetu.institute.repository.TrustRepository;
import org.yuktisetu.profile.db.StudentProfile;
import org.yuktisetu.profile.repository.StudentProfileRepository;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Database-backed cover for bulk student import.
 *
 * This is the single highest-risk class in the microservices migration.
 * `createBulkStudentProfile` writes `users` + `user_role_assignments` (identity)
 * AND `student_profiles` (profile) inside ONE `@Transactional`, after reading
 * colleges and departments (institute). Three contexts, one atomic write.
 *
 * Phase 5 splits it: identity writes stay in auth-service, which emits
 * `identity.student.onboarded`; user-service consumes that and writes the
 * profile — forward-only, no saga, because a user with no profile is already a
 * legal state. These assertions are what prove the split preserved behaviour,
 * and they are written against observable OUTCOMES (which rows exist, with what
 * values) rather than against the transaction, so they survive the move.
 *
 * Not @Transactional — the service manages its own, and an enclosing test
 * transaction would hide exactly the commit semantics under test.
 */
@SpringBootTest
@ActiveProfiles("test")
class BulkStudentImportIntegrationTest {

    @Autowired BulkStudentService bulkStudentService;
    @Autowired UserRepository userRepository;
    @Autowired UserRoleAssignmentRepository roleAssignmentRepository;
    @Autowired StudentProfileRepository profileRepository;
    @Autowired TrustRepository trustRepository;
    @Autowired CollegeRepository collegeRepository;
    @Autowired DepartmentRepository departmentRepository;

    private static final AtomicLong SEQ = new AtomicLong(800000);

    private College college;
    private Department department;
    private UserPrincipal actor;

    @BeforeEach
    void seedInstitute() {
        Date now = new Date();
        Trust t = new Trust();
        t.setName("PCET");
        t.setCode("T-" + UUID.randomUUID().toString().substring(0, 6));
        t.setStatus(TenantStatus.ACTIVE);
        t = trustRepository.save(t);

        college = new College();
        college.setTrust(t);
        college.setName("PCCOE");
        college.setCode("C-" + UUID.randomUUID().toString().substring(0, 6));
        college.setPrimaryContactName("TnP Officer");
        college.setPrimaryContactEmail("tnp@example.test");
        college.setStatus(TenantStatus.ACTIVE);
        college = collegeRepository.save(college);

        department = new Department();
        department.setCollege(college);
        department.setName("Computer Engineering");
        department.setCode("D-" + UUID.randomUUID().toString().substring(0, 6));
        department.setStatus(TenantStatus.ACTIVE);
        department = departmentRepository.save(department);

        // The importing admin. A real User row is needed because the created
        // students' audit columns reference it.
        long n = SEQ.incrementAndGet();
        User admin = userRepository.save(User.builder()
                .email("admin" + n + "@example.test").phone("8" + String.format("%09d", n)).password("x")
                .firstName("TnP").lastName("Admin").status(UserStatus.ACTIVE)
                .createdAt(now).updatedAt(now).isDeleted(false).build());
        actor = new UserPrincipal(admin.getId(), admin.getEmail(),
                List.of(new UserPrincipal.RoleClaim("TNP_SUPER_ADMIN", null, null)));
    }

    private StudentRequest aStudent() {
        long n = SEQ.incrementAndGet();
        StudentRequest s = new StudentRequest();
        s.setFirstName("Asha");
        s.setLastName("Patil");
        s.setEmail("stu" + n + "@example.test");
        s.setPhone("7" + String.format("%09d", n));   // 10 digits: the validator demands ^\\d{10,15}$
        s.setPrn("PRN" + n);
        s.setDegree("B.Tech");
        s.setGraduationYear(2027);
        s.setCGPA(8.1);
        s.setPercentage10th(88.0);
        s.setPercentage12th(76.0);
        s.setSem1Gpa(7.9);
        s.setCollegeCode(college.getCode());
        s.setDepartmentCode(department.getCode());
        return s;
    }

    private BulkStudentCreateResponse importOf(StudentRequest... students) {
        BulkStudentRequest req = new BulkStudentRequest();
        req.setStudents(List.of(students));
        return bulkStudentService.createBulkStudentProfile(actor, req);
    }

    // ------------------------------------------------------------------ tests

    /**
     * The atomicity contract, stated as an outcome rather than as a transaction:
     * after a successful import, all THREE rows exist for the student. Phase 5
     * makes this eventually consistent instead of atomic; this assertion is what
     * must still hold once the profile write arrives via an event.
     */
    @Test
    @DisplayName("a successful import creates the user, the STUDENT role assignment AND the profile")
    void importCreatesAllThreeRows() {
        StudentRequest s = aStudent();

        BulkStudentCreateResponse res = importOf(s);
        assertEquals(1, res.getSuccessfulStudents().size(), "expected one success");
        assertTrue(res.getFailedStudents().isEmpty());

        User created = userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(s.getEmail()).orElse(null);
        assertNotNull(created, "identity row must exist");

        List<UserRoleAssignment> roles = roleAssignmentRepository
                .findByUserIdAndIsActiveTrue(created.getId());
        assertEquals(1, roles.size());
        assertEquals(RoleType.STUDENT, roles.get(0).getRole());

        StudentProfile profile = profileRepository.findByUserIdAndUser_IsDeletedFalse(created.getId()).orElse(null);
        assertNotNull(profile, "profile row must exist -- this is the cross-context half");
        assertEquals(2027, profile.getGraduationYear());
        assertEquals(8.1, profile.getCgpa());
    }

    /**
     * Bulk-imported students are created PENDING_ACTIVATION, and drive targeting
     * deliberately counts only ACTIVE accounts. So a freshly imported cohort
     * resolves to ZERO eligible candidates until the invites are accepted — a
     * behaviour that looks like a bug the first time anyone hits it, and must not
     * change silently.
     */
    @Test
    @DisplayName("imported students start PENDING_ACTIVATION, so they are not yet drive-eligible")
    void importedStudentsArePendingActivation() {
        StudentRequest s = aStudent();
        importOf(s);

        User created = userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(s.getEmail()).orElseThrow();
        assertEquals(UserStatus.PENDING_ACTIVATION, created.getStatus(),
                "an imported student must not be ACTIVE until they accept their invite");
    }

    /** The role assignment carries the scope; the profile carries the academics. */
    @Test
    @DisplayName("the role assignment carries college, department and degree scope")
    void roleAssignmentCarriesScope() {
        StudentRequest s = aStudent();
        importOf(s);

        User created = userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(s.getEmail()).orElseThrow();
        UserRoleAssignment ra = roleAssignmentRepository.findByUserIdAndIsActiveTrue(created.getId()).get(0);

        assertEquals(college.getId(), ra.getCollegeId(),
                "scope is resolved from collegeCode, and is a bare id since Phase 2");
        assertEquals(department.getId(), ra.getDeptId());
        assertEquals("B.Tech", ra.getDegree(), "degree lives on the assignment, not the profile");
    }

    @Test
    @DisplayName("several students import in one call, each with their own three rows")
    void multipleStudentsImportIndependently() {
        StudentRequest a = aStudent();
        StudentRequest b = aStudent();

        BulkStudentCreateResponse res = importOf(a, b);

        assertEquals(2, res.getSuccessfulStudents().size());
        for (StudentRequest s : List.of(a, b)) {
            User u = userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(s.getEmail()).orElse(null);
            assertNotNull(u, "each student gets their own identity row");
            assertNotNull(profileRepository.findByUserIdAndUser_IsDeletedFalse(u.getId()).orElse(null));
        }
    }

    /**
     * Field-level validation IS per-row: a bad phone number is collected into
     * failedStudents and the rest of the upload still lands. This is the half
     * that behaves the way an operator expects.
     */
    @Test
    @DisplayName("a row failing field validation is reported while the rest of the batch succeeds")
    void fieldValidationFailsOneRowOnly() {
        StudentRequest good = aStudent();
        StudentRequest bad = aStudent();
        bad.setPhone("123");                       // validator demands 10-15 digits

        BulkStudentCreateResponse res = importOf(good, bad);

        assertEquals(1, res.getSuccessfulStudents().size(), "the valid row must still be created");
        assertEquals(1, res.getFailedStudents().size(), "the invalid row must be reported, not thrown");
        assertNotNull(userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(good.getEmail()).orElse(null));
        assertNull(userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(bad.getEmail()).orElse(null));
    }

    /**
     * FIXED (was a bug): scope is resolved PER ROW.
     *
     * BulkStudentService used to read the college and department from
     * `students.getFirst()` and apply that one pair to the whole batch, so a CSV
     * spanning two departments filed everyone under the first row's department --
     * silently, with the import reporting full success. It now resolves each
     * row's own codes against maps pre-fetched in two queries, so no database
     * call happens inside the loop.
     */
    @Test
    @DisplayName("each student is filed under their OWN college and department")
    void eachStudentKeepsTheirOwnScope() {
        Department other = new Department();
        other.setCollege(college);
        other.setName("Mechanical Engineering");
        other.setCode("D-" + UUID.randomUUID().toString().substring(0, 6));
        other.setStatus(TenantStatus.ACTIVE);
        other = departmentRepository.save(other);

        StudentRequest inComputer = aStudent();
        StudentRequest inMechanical = aStudent();
        inMechanical.setDepartmentCode(other.getCode());

        BulkStudentCreateResponse res = importOf(inComputer, inMechanical);
        assertEquals(2, res.getSuccessfulStudents().size());

        User comp = userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(inComputer.getEmail()).orElseThrow();
        User mech = userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(inMechanical.getEmail()).orElseThrow();

        assertEquals(department.getId(),
                roleAssignmentRepository.findByUserIdAndIsActiveTrue(comp.getId()).get(0).getDeptId());
        assertEquals(other.getId(),
                roleAssignmentRepository.findByUserIdAndIsActiveTrue(mech.getId()).get(0).getDeptId(),
                "the Mechanical student must land in Mechanical, not in the first row's department");
    }

    /**
     * The same fix turned an all-or-nothing abort into a per-row failure: an
     * unknown college code now fails only that student, and the rest of the
     * upload lands. Previously one mistyped code cost a whole 200-student CSV.
     */
    @Test
    @DisplayName("an unknown college code fails only that row, not the whole upload")
    void unknownCollegeCodeFailsOnlyThatRow() {
        StudentRequest good = aStudent();
        StudentRequest bad = aStudent();
        bad.setCollegeCode("NOPE-" + UUID.randomUUID().toString().substring(0, 6));

        BulkStudentCreateResponse res = importOf(good, bad);

        assertEquals(1, res.getSuccessfulStudents().size(), "the valid row still imports");
        assertEquals(1, res.getFailedStudents().size(), "the bad row is reported");
        assertNotNull(userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(good.getEmail()).orElse(null));
        assertNull(userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(bad.getEmail()).orElse(null));
    }

    /**
     * Same class of behaviour, different trigger: a duplicate email is caught by
     * the unique constraint at flush time, outside the per-row handler, so it
     * also aborts the batch. The pre-existing user is untouched, which is the
     * part that actually matters.
     */
    @Test
    @DisplayName("a duplicate email aborts the batch and leaves the existing user intact")
    void duplicateEmailAbortsTheBatch() {
        StudentRequest first = aStudent();
        importOf(first);
        Long originalId = userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(first.getEmail())
                .orElseThrow().getId();

        StudentRequest duplicate = aStudent();
        duplicate.setEmail(first.getEmail());

        assertThrows(RuntimeException.class, () -> importOf(duplicate));

        User still = userRepository.findByEmailIgnoreCaseAndIsDeletedFalse(first.getEmail()).orElseThrow();
        assertEquals(originalId, still.getId(), "the original user must survive a failed re-import");
    }
}

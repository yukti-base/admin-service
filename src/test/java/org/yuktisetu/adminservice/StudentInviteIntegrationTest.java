package org.yuktisetu.adminservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.yuktisetu.adminservice.dto.BulkStudentRequest;
import org.yuktisetu.adminservice.dto.PendingInviteDto;
import org.yuktisetu.adminservice.dto.ResendInvitesResponse;
import org.yuktisetu.adminservice.dto.StudentRequest;
import org.yuktisetu.adminservice.service.BulkStudentService;
import org.yuktisetu.adminservice.service.StudentInviteService;
import org.yuktisetu.core.notification.model.InviteRecipient;
import org.yuktisetu.core.notification.service.EmailService;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.identity.repository.UserRepository;
import org.yuktisetu.institute.db.College;
import org.yuktisetu.institute.db.Degree;
import org.yuktisetu.institute.repository.DegreeRepository;
import org.yuktisetu.institute.db.Department;
import org.yuktisetu.institute.db.Trust;
import org.yuktisetu.institute.model.TenantStatus;
import org.yuktisetu.institute.repository.CollegeRepository;
import org.yuktisetu.institute.repository.DepartmentRepository;
import org.yuktisetu.institute.repository.TrustRepository;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Regression cover for the 2026-10-18 incident: ten students imported, zero
 * invite emails. The async invite job ran before the import transaction
 * committed, saw no users, and quietly sent nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
class StudentInviteIntegrationTest {

    @Autowired BulkStudentService bulkStudentService;
    @Autowired StudentInviteService studentInviteService;
    @Autowired UserRepository userRepository;
    @Autowired TrustRepository trustRepository;
    @Autowired CollegeRepository collegeRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired DegreeRepository degreeRepository;

    @Autowired JdbcTemplate jdbc;

    @MockitoBean EmailService emailService;

    private static final AtomicLong SEQ = new AtomicLong(900000);

    private College college;
    private Department department;
    private UserPrincipal actor;

    @BeforeEach
    void seed() {
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
        college.getDegrees().add(degree("BTECH", "B.Tech"));
        college = collegeRepository.save(college);

        department = new Department();
        department.setCollege(college);
        department.setName("Computer Engineering");
        department.setDegree(degree("BTECH", "B.Tech"));
        department.setCode("D-" + UUID.randomUUID().toString().substring(0, 6));
        department.setStatus(TenantStatus.ACTIVE);
        department = departmentRepository.save(department);

        long n = SEQ.incrementAndGet();
        User admin = userRepository.save(User.builder()
                .email("admin" + n + "@example.test").phone("8" + String.format("%09d", n)).password("x")
                .firstName("TnP").lastName("Admin").status(UserStatus.ACTIVE)
                .createdAt(now).updatedAt(now).isDeleted(false).build());
        actor = new UserPrincipal(admin.getId(), admin.getEmail(),
                List.of(new UserPrincipal.RoleClaim("TNP_SUPER_ADMIN", null, null)));
    }

    private Degree degree(String code, String name) {
        return degreeRepository.findByIsDeletedFalseOrderByNameAsc().stream()
                .filter(d -> d.getCode().equals(code)).findFirst()
                .orElseGet(() -> {
                    Degree d = new Degree();
                    d.setCode(code);
                    d.setName(name);
                    d.setStatus(TenantStatus.ACTIVE);
                    return degreeRepository.save(d);
                });
    }

    private StudentRequest aStudent() {
        long n = SEQ.incrementAndGet();
        StudentRequest s = new StudentRequest();
        s.setFirstName("Asha");
        s.setLastName("Patil");
        s.setEmail("stu" + n + "@example.test");
        s.setPhone("7" + String.format("%09d", n));
        s.setPrn("PRN" + n);
        s.setDegree("B.Tech");
        s.setGraduationYear(2027);
        s.setCollegeCode(college.getCode());
        s.setDepartmentCode(department.getCode());
        return s;
    }

    private void importOf(StudentRequest... students) {
        BulkStudentRequest req = new BulkStudentRequest();
        req.setStudents(List.of(students));
        bulkStudentService.createBulkStudentProfile(actor, req);
    }

    @Test
    @DisplayName("importing students sends their invites once the import has committed")
    void importSendsInvitesAfterCommit() {
        StudentRequest a = aStudent();
        StudentRequest b = aStudent();

        importOf(a, b);

        // The send is async, so wait for it. Before the fix this never fired.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InviteRecipient>> sent = ArgumentCaptor.forClass(List.class);
        verify(emailService, timeout(10_000)).sendBulkInviteEmails(sent.capture());
        List<String> emails = sent.getValue().stream().map(InviteRecipient::getEmail).toList();
        assertTrue(emails.containsAll(List.of(a.getEmail(), b.getEmail())), "both invites go out: " + emails);
    }

    @Test
    @DisplayName("pending list shows un-activated students, and resend skips anyone already activated")
    void pendingListAndResend() {
        StudentRequest a = aStudent();
        StudentRequest b = aStudent();
        importOf(a, b);
        verify(emailService, timeout(10_000)).sendBulkInviteEmails(anyList());
        clearInvocations(emailService);

        List<PendingInviteDto> pending = studentInviteService.listPending(actor);
        PendingInviteDto pa = pending.stream().filter(p -> p.getEmail().equals(a.getEmail())).findFirst().orElseThrow();
        PendingInviteDto pb = pending.stream().filter(p -> p.getEmail().equals(b.getEmail())).findFirst().orElseThrow();

        // b activates before the resend
        jdbc.update("update users set status = 'ACTIVE' where id = ?", pb.getUserId());

        ResendInvitesResponse res = studentInviteService.resend(actor, List.of(pa.getUserId(), pb.getUserId()));

        assertEquals(1, res.getSentCount());
        assertEquals(List.of(pb.getUserId()), res.getSkippedUserIds());
        verify(emailService, times(1)).sendBulkInviteEmails(argThat(l -> l.size() == 1
                && l.get(0).getEmail().equals(a.getEmail())));
        assertTrue(studentInviteService.listPending(actor).stream()
                .noneMatch(p -> p.getUserId().equals(pb.getUserId())), "activated student leaves the pending list");
    }
}

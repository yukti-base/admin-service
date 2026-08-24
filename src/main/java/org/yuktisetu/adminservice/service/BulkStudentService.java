package org.yuktisetu.adminservice.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.dto.BulkStudentCreateResponse;
import org.yuktisetu.adminservice.dto.BulkStudentRequest;
import org.yuktisetu.adminservice.dto.StudentRequest;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.db.College;
import org.yuktisetu.db.Department;
import org.yuktisetu.db.StudentProfile;
import org.yuktisetu.db.User;
import org.yuktisetu.db.UserRoleAssignment;
import org.yuktisetu.model.RoleType;
import org.yuktisetu.model.UserStatus;
import org.yuktisetu.repository.CollegeRepository;
import org.yuktisetu.repository.DepartmentRepository;
import org.yuktisetu.repository.StudentProfileRepository;
import org.yuktisetu.repository.UserRepository;
import org.yuktisetu.repository.UserRoleAssignmentRepository;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@Service
@AllArgsConstructor
@Slf4j
public class BulkStudentService {

    private final UserRepository userRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final TriggerNotificationService triggerNotificationService;
    private final PasswordEncoder passwordEncoder;
    private final CollegeRepository collegeRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRoleAssignmentRepository userRoleAssignmentRepository;

    @Transactional
    public BulkStudentCreateResponse createBulkStudentProfile(UserPrincipal userPrincipal, BulkStudentRequest request) {
        try {
            User loggedInUser = null;
            if (!userPrincipal.hasRole(String.valueOf(RoleType.STUDENT))) {
                loggedInUser = userRepository.findById(userPrincipal.userId()).orElse(null);
                log.info("User {} has {} authority to create bulk student profiles", userPrincipal.userId(), userPrincipal.roles().getFirst());
            } else {
                log.warn("User {} does not have sufficient authority to create bulk student profiles", userPrincipal.userId());
                throw new AdminExceptions.InsufficientAuthorityException();
            }

            if (request.getStudents() == null || request.getStudents().isEmpty()) {
                throw new AdminExceptions.InvalidRequestException("Student list cannot be empty");
            }

            List<StudentRequest> studentRequests = request.getStudents();
            List<User> usersToSave = new ArrayList<>();
            List<StudentProfile> studentProfilesToSave = new ArrayList<>();
            List<BulkStudentCreateResponse.BulkStudentSuccessDto> successfulStudents = new ArrayList<>();
            List<BulkStudentCreateResponse.BulkStudentFailureDto> failedStudents = new ArrayList<>();

            log.info("Starting bulk student processing for {} students", studentRequests.size());
            Date now = new Date();

            for (int i = 0; i < studentRequests.size(); i++) {
                StudentRequest studentRequest = studentRequests.get(i);
                try {
                    // Validate required fields
                    validateStudentRequest(studentRequest, i);

                    // Generate password: lastname.first 5 prn digits
                    String password = generatePassword(studentRequest.getLastName(), studentRequest.getPrn());

                    // Create User entity
                    User user = User.builder()
                            .email(studentRequest.getEmail().toLowerCase().trim())
                            .phone(studentRequest.getPhone().trim())
                            .firstName(studentRequest.getFirstName().trim())
                            .lastName(studentRequest.getLastName() != null ? studentRequest.getLastName().trim() : "")
                            .password(passwordEncoder.encode(password)) // Will be replaced when user accepts invite
                            .status(UserStatus.PENDING_ACTIVATION)
                            .createdAt(now)
                            .updatedAt(now)
                            .isDeleted(false)
                            .build();

                    // Create StudentProfile entity
                    StudentProfile studentProfile = StudentProfile.builder()
                            .user(user)
                            .dateOfBirth(null) // Not provided in request
                            .address(null) // Not provided in request
                            .institution(null) // Not provided in request
                            .degree(null) // Not provided in request
                            .branch(null) // Not provided in request
                            .cgpa(studentRequest.getCGPA())
                            .graduationYear(null) // Not provided in request
                            .tenthPercentage(studentRequest.getPercentage10th())
                            .twelfthPercentage(studentRequest.getPercentage12th())
                            .createdAt(now)
                            .updatedAt(now)
                            .build();

                    // Add to batch lists
                    usersToSave.add(user);
                    studentProfilesToSave.add(studentProfile);

                    // Prepare success response
                    BulkStudentCreateResponse.BulkStudentSuccessDto successDto = BulkStudentCreateResponse.BulkStudentSuccessDto.builder()
                            .firstName(studentRequest.getFirstName())
                            .lastName(studentRequest.getLastName())
                            .email(studentRequest.getEmail())
                            .phone(studentRequest.getPhone())
                            .prn(studentRequest.getPrn())
                            .collegeCode(studentRequest.getCollegeCode())
                            .departmentCode(studentRequest.getDepartmentCode())
                            .message("Student processed successfully")
                            .build();
                    successfulStudents.add(successDto);

                    log.info("Successfully processed student {}: {}", i + 1, studentRequest.getEmail());

                } catch (Exception e) {
                    // Prepare failure response
                    String failedField = extractFailedField(e.getMessage());
                    BulkStudentCreateResponse.BulkStudentFailureDto failureDto = BulkStudentCreateResponse.BulkStudentFailureDto.builder()
                            .firstName(studentRequest.getFirstName() != null ? studentRequest.getFirstName() : "")
                            .lastName(studentRequest.getLastName() != null ? studentRequest.getLastName() : "")
                            .email(studentRequest.getEmail() != null ? studentRequest.getEmail() : "")
                            .phone(studentRequest.getPhone() != null ? studentRequest.getPhone() : "")
                            .collegeCode(studentRequest.getCollegeCode() != null ? studentRequest.getCollegeCode() : "")
                            .departmentCode(studentRequest.getDepartmentCode() != null ? studentRequest.getDepartmentCode() : "")
                            .errorMessage(e.getMessage())
                            .failedField(failedField)
                            .build();
                    failedStudents.add(failureDto);

                    log.warn("Failed to process student {}: {} - {}", i + 1,
                            studentRequest.getEmail() != null ? studentRequest.getEmail() : "unknown", e.getMessage());
                }
            }
            String collegeCode = request.getStudents().getFirst().getCollegeCode();
            String departmentCode = request.getStudents().getFirst().getDepartmentCode();

            College college = collegeRepository.findByCodeAndIsDeletedFalse(collegeCode);
            Department department = departmentRepository.findByCodeAndIsDeletedFalse(departmentCode);

            if (Objects.isNull(college) || Objects.isNull(department)) {
                throw new AdminExceptions.InvalidRequestException("Invalid college or department code provided");
            }
            // Save all users in batch
            List<User> savedUsers = new ArrayList<>();
            if (!usersToSave.isEmpty()) {
                savedUsers = userRepository.saveAll(usersToSave);

                // Save all student profiles in batch (need to set the saved users)
                for (int i = 0; i < studentProfilesToSave.size(); i++) {
                    studentProfilesToSave.get(i).setUser(savedUsers.get(i));
                }
                studentProfileRepository.saveAll(studentProfilesToSave);

                List<UserRoleAssignment> userRoleAssignments = new ArrayList<>();
                for (User user : savedUsers) {
                    UserRoleAssignment assignment = new UserRoleAssignment();
                    assignment.setUser(user);
                    assignment.setRole(RoleType.STUDENT);
                    assignment.setAssignedAt(new Date());
                    assignment.setAssignedBy(loggedInUser);
                    assignment.setActive(true);
                    assignment.setCollege(college);
                    assignment.setDepartment(department);
                    userRoleAssignments.add(assignment);

                }
                userRoleAssignmentRepository.saveAll(userRoleAssignments);

                log.info("Saved {} users and {} student profiles to database",
                        savedUsers.size(), studentProfilesToSave.size());
            }

            // Build and return response (DB operation completed)
            BulkStudentCreateResponse response = BulkStudentCreateResponse.builder()
                    .totalProcessed(studentRequests.size())
                    .successfulCount(successfulStudents.size())
                    .failedCount(failedStudents.size())
                    .successfulStudents(successfulStudents)
                    .failedStudents(failedStudents)
                    .build();

            log.info("Bulk student DB processing completed. Total: {}, Successful: {}, Failed: {}",
                    studentRequests.size(), successfulStudents.size(), failedStudents.size());

            List<Long> userIds = savedUsers.stream().map(User::getId).toList();
            log.info("Count User IDs of successfully saved students: {}", userIds.size());
            // Trigger asynchronous invite processing (after DB transaction commits)
            triggerNotificationService.processStudentInvitesAsync(userIds, successfulStudents);

            return response;
        } catch (Exception e) {
            log.error("Unexpected error during bulk student processing: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private void validateStudentRequest(StudentRequest studentRequest, int index) {
        if (studentRequest.getFirstName() == null || studentRequest.getFirstName().isBlank()) {
            throw new AdminExceptions.InvalidRequestException(
                    String.format("Student at position %d: firstName is required", index + 1));
        }
        if (studentRequest.getLastName() == null || studentRequest.getLastName().isBlank()) {
            throw new AdminExceptions.InvalidRequestException(
                    String.format("Student at position %d: lastName is required", index + 1));
        }
        if (studentRequest.getEmail() == null || studentRequest.getEmail().isBlank()) {
            throw new AdminExceptions.InvalidRequestException(
                    String.format("Student at position %d: email is required", index + 1));
        }
        if (studentRequest.getPhone() == null || studentRequest.getPhone().isBlank()) {
            throw new AdminExceptions.InvalidRequestException(
                    String.format("Student at position %d: phone is required", index + 1));
        }
        if (studentRequest.getPrn() == null) {
            throw new AdminExceptions.InvalidRequestException(
                    String.format("Student at position %d: prn is required", index + 1));
        }
        if (studentRequest.getCollegeCode() == null || studentRequest.getCollegeCode().isBlank()) {
            throw new AdminExceptions.InvalidRequestException(
                    String.format("Student at position %d: collegeCode is required", index + 1));
        }
        if (studentRequest.getDepartmentCode() == null || studentRequest.getDepartmentCode().isBlank()) {
            throw new AdminExceptions.InvalidRequestException(
                    String.format("Student at position %d: departmentCode is required", index + 1));
        }
        
        // Additional validation for email format (basic)
        String email = studentRequest.getEmail().toLowerCase().trim();
        if (!email.matches("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$")) {
            throw new AdminExceptions.InvalidRequestException(
                    String.format("Student at position %d: invalid email format", index + 1));
        }
        
        // Additional validation for phone (basic digits only)
        String phone = studentRequest.getPhone().trim();
        if (!phone.matches("^\\d{10,15}$")) {
            throw new AdminExceptions.InvalidRequestException(
                    String.format("Student at position %d: invalid phone format", index + 1));
        }
    }

    private String generatePassword(String lastName, String prn) {
        if (lastName == null || lastName.isEmpty()) {
            lastName = "Student";
        }
        String prnStr = prn != null ? prn : "00000";
        String prnPart = prnStr.length() >= 5 ? prnStr.substring(0, 5) : String.format("%05d", Integer.parseInt(prnStr));
        return String.format("%s.%s", lastName.toLowerCase(), prnPart);
    }

    private String extractFailedField(String errorMessage) {
        if (errorMessage == null) return "unknown";
        if (errorMessage.contains("firstName")) return "firstName";
        if (errorMessage.contains("lastName")) return "lastName";
        if (errorMessage.contains("email")) return "email";
        if (errorMessage.contains("phone")) return "phone";
        if (errorMessage.contains("prn")) return "prn";
        return "validation";
    }
}

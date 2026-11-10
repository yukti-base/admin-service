package org.yuktisetu.adminservice.people;

import org.springframework.format.annotation.DateTimeFormat;
import org.yuktisetu.drive.model.OfferStatus;
import org.yuktisetu.drive.model.OfferType;
import org.yuktisetu.identity.model.RoleType;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.profile.model.CertificationVerificationStatus;
import org.yuktisetu.profile.model.CodingPlatform;
import org.yuktisetu.profile.model.DynamicFieldDataType;
import org.yuktisetu.profile.model.StudentDocumentType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Requests and responses for the admin portal's people search.
 *
 * Criteria are bound from query parameters (repeat a key for a list:
 * {@code collegeIds=1&collegeIds=2}). Every field is optional and a null one
 * adds no condition. Booleans are boxed: an omitted flag means "don't filter",
 * never "false".
 */
public final class PeopleDtos {

    private PeopleDtos() {}

    // ---------------------------------------------------------------- criteria

    /**
     * Which people to include by lifecycle. ACTIVE is the default.
     * <ul>
     *   <li>ACTIVE: an active role assignment on an ACTIVE account.</li>
     *   <li>PENDING_INVITE: invited, never activated (PENDING_ACTIVATION).</li>
     *   <li>DEACTIVATED: the role was revoked, or the account is INACTIVE, SUSPENDED or LOCKED.</li>
     *   <li>ANY: all of the above and every other account status.</li>
     * </ul>
     */
    public enum PersonStatus { ACTIVE, PENDING_INVITE, DEACTIVATED, ANY }

    /** Placement standing, derived from live offers. See PeopleSpecifications.placement. */
    public enum PlacementFilter { PLACED, OFFER_PENDING, UNPLACED, HOLDS_INTERNSHIP }

    public enum SkillMatch { ANY, ALL }

    public record StudentSearchCriteria(
            String q,
            List<Long> collegeIds,
            List<Long> departmentIds,
            List<Long> degreeIds,
            List<Integer> batches,
            PersonStatus status,
            List<UserStatus> accountStatuses,
            Boolean includeDeleted,
            Boolean neverLoggedIn,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            // academics
            Double cgpaMin,
            Double cgpaMax,
            Double tenthMin,
            Double twelfthMin,
            Double diplomaMin,
            Integer semester,
            Double semesterGpaMin,
            Double coCubesMin,
            Integer activeBacklogsMax,
            Integer deadBacklogsMax,
            Integer totalBacklogsMax,
            Integer gapYearsMax,
            // profile
            List<String> skills,
            SkillMatch skillMatch,
            List<CodingPlatform> codingPlatforms,
            Boolean hasWorkExperience,
            Boolean currentlyInterning,
            Boolean hasScorableCertification,
            List<CertificationVerificationStatus> certificationStatuses,
            Boolean hasPrimaryResume,
            List<StudentDocumentType> missingDocTypes,
            // placement
            PlacementFilter placement,
            Integer offerCountMin,
            List<Long> offerCompanyIds,
            List<OfferType> offerTypes,
            // advanced fields: "definitionId|OPERATOR|value", at most three
            List<String> dynamic) {}

    public record StaffSearchCriteria(
            String q,
            List<RoleType> roles,
            List<Long> collegeIds,
            List<Long> departmentIds,
            PersonStatus status,
            List<UserStatus> accountStatuses,
            Boolean includeDeleted,
            Boolean neverLoggedIn,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastLoginBefore,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate assignedFrom,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate assignedTo) {}

    /** Operators for an advanced-field condition. Which apply depends on the field's data type. */
    public enum DynamicOperator { EQ, CONTAINS, GTE, LTE, IN }

    /** One parsed advanced-field condition. For IN, {@code value} is a comma-separated list. */
    public record DynamicCondition(Long definitionId, DynamicFieldDataType dataType, DynamicOperator operator, String value) {}

    // ---------------------------------------------------------------- results

    /**
     * One page of results. Our own shape rather than Spring's PageImpl, so the
     * JSON does not depend on how Spring Data chooses to serialise a Page. It
     * keeps {@code content} and {@code last}, which the portal already reads.
     */
    public record PeoplePage<T>(List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {}

    public record AssignmentView(
            Long id,
            RoleType role,
            Long collegeId,
            String collegeName,
            String collegeCode,
            String trustName,
            Long departmentId,
            String departmentName,
            Long degreeId,
            String degreeName,
            boolean active,
            Instant assignedAt,
            Instant revokedAt) {}

    public record StudentRow(
            Long userId,
            String firstName,
            String lastName,
            String email,
            String phone,
            String prn,
            UserStatus accountStatus,
            boolean deleted,
            boolean assignmentActive,
            String collegeName,
            String collegeCode,
            String departmentName,
            String degreeName,
            Integer batch,
            Double cgpa,
            Integer activeBacklogs,
            PlacementFilter placement,
            int liveOfferCount,
            boolean holdsInternship,
            String latestCompany,
            boolean hasPrimaryResume,
            Instant lastLoginAt) {}

    public record StaffRow(
            Long userId,
            String firstName,
            String lastName,
            String email,
            String phone,
            UserStatus accountStatus,
            boolean deleted,
            Instant lastLoginAt,
            Instant createdAt,
            List<AssignmentView> assignments) {}

    // ---------------------------------------------------------------- detail

    public record PersonDetail(Account account, List<AssignmentView> assignments, StudentDetail student) {}

    public record Account(
            Long userId,
            String firstName,
            String lastName,
            String email,
            String phone,
            Instant dateOfBirth,
            String address,
            UserStatus status,
            boolean deleted,
            Instant deletedAt,
            Instant lastLoginAt,
            Instant createdAt) {}

    public record StudentDetail(
            String prn,
            Integer graduationYear,
            Double cgpa,
            Double tenthPercentage,
            Double twelfthPercentage,
            Double diplomaPercentage,
            List<Double> semesterGpas,
            Double coCubesScore,
            Integer activeBacklogs,
            Integer deadBacklogs,
            Integer totalBacklogs,
            Integer gapYears,
            List<String> skills,
            List<CodingView> codingProfiles,
            List<LinkView> professionalProfiles,
            List<ProjectView> projects,
            List<ExperienceView> workExperiences,
            List<AchievementView> achievements,
            List<CertificationView> certifications,
            List<DynamicValueView> advancedFields,
            List<OfferView> offers,
            List<ApplicationView> applications) {}

    public record CodingView(CodingPlatform platform, String platformLabel, String username, String profileLink, Double rating) {}

    public record LinkView(String platform, String profileLink) {}

    public record ProjectView(String title, String projectLink, String description, String technologies) {}

    public record ExperienceView(String company, String role, LocalDate startDate, LocalDate endDate,
                                 boolean current, String duration, String description) {}

    public record AchievementView(String title, String description) {}

    public record CertificationView(String name, String issuer, LocalDate issuedOn, LocalDate expiresOn,
                                    String credentialUrl, CertificationVerificationStatus status, boolean scorable) {}

    public record DynamicValueView(Long definitionId, String fieldKey, String label, DynamicFieldDataType dataType,
                                   boolean definitionActive, Object value) {}

    public record OfferView(Long id, String companyName, String roleTitle, OfferType offerType, OfferStatus status,
                            BigDecimal ctcAmount, String ctcCurrency, LocalDate offerDate, boolean countsTowardPlacement) {}

    public record ApplicationView(Long postingId, String postingTitle, String companyName, String status, Instant appliedAt) {}

    // ---------------------------------------------------------------- filter options

    public record PeopleFilterOptions(
            List<RoleType> visibleRoles,
            boolean canSearchStudents,
            boolean canSearchStaff,
            boolean canIncludeDeleted,
            /** Non-null when every grant is pinned to one college / department -- the UI locks the picker. */
            Long lockedCollegeId,
            Long lockedDepartmentId,
            List<CollegeOption> colleges,
            List<Integer> batches,
            List<Option> companies,
            List<DynamicFieldOption> dynamicFields,
            /** The most common skills, lower-cased, for the skill picker's suggestions. */
            List<String> skillSuggestions,
            List<UserStatus> accountStatuses,
            List<CodingPlatform> codingPlatforms,
            List<CertificationVerificationStatus> certificationStatuses,
            List<StudentDocumentType> documentTypes,
            List<OfferType> offerTypes) {}

    public record Option(Long id, String name) {}

    public record CollegeOption(Long id, String name, String code, String trustName,
                                List<DepartmentOption> departments, List<Option> degrees) {}

    public record DepartmentOption(Long id, String name, String code, Long degreeId, String degreeName) {}

    /** {@code validationRules} is passed through as stored (JSON); the UI reads ENUM options from it. */
    public record DynamicFieldOption(Long id, String fieldKey, String label, DynamicFieldDataType dataType, String validationRules) {}
}

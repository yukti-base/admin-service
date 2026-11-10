package org.yuktisetu.adminservice.people;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.people.PeopleDtos.*;
import org.yuktisetu.adminservice.people.PeopleSpecifications.SortKey;
import org.yuktisetu.adminservice.people.PeopleVisibility.Grant;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.exception.ForbiddenException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.PlacementOffer;
import org.yuktisetu.drive.model.OfferStatus;
import org.yuktisetu.drive.model.OfferType;
import org.yuktisetu.drive.model.OfferTypes;
import org.yuktisetu.drive.model.ReapplicationRules;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.db.UserRoleAssignment;
import org.yuktisetu.identity.model.RoleType;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.identity.repository.UserRepository;
import org.yuktisetu.identity.repository.UserRoleAssignmentRepository;
import org.yuktisetu.institute.db.College;
import org.yuktisetu.institute.db.Department;
import org.yuktisetu.profile.db.StudentDocument;
import org.yuktisetu.profile.db.StudentDynamicFieldValue;
import org.yuktisetu.profile.db.StudentProfile;
import org.yuktisetu.profile.model.CertificationVerificationStatus;
import org.yuktisetu.profile.model.CodingPlatform;
import org.yuktisetu.profile.model.DynamicFieldDataType;
import org.yuktisetu.profile.model.StudentDocumentType;
import org.yuktisetu.profile.repository.StudentDocumentRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The admin portal's people search: who the caller may find (PeopleVisibility),
 * what they asked for (PeopleSpecifications), and the rows they see.
 *
 * A page costs a fixed number of queries whatever its size: the paged select,
 * its count, then one batch each for assignments, profiles, live offers and
 * primary resumes, all keyed into maps. Nothing queries per row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeopleSearchService {

    static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_DYNAMIC_CONDITIONS = 3;
    private static final int SKILL_SUGGESTIONS = 200;
    private static final int DETAIL_APPLICATIONS = 100;

    private final UserRepository userRepository;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final StudentDocumentRepository documentRepository;
    private final PeopleVisibility visibility;
    private final EntityManager em;

    // ================================================================ search

    @Transactional(readOnly = true)
    public PeoplePage<StudentRow> searchStudents(UserPrincipal actor, StudentSearchCriteria criteria,
                                                 int page, int size, String sort, String direction) {
        try {
            List<Grant> grants = requireGrants(actor);
            boolean includeDeleted = includeDeleted(actor, criteria.includeDeleted());
            validateStudentCriteria(criteria);
            List<DynamicCondition> dynamic = parseDynamic(criteria.dynamic());
            SortKey sortKey = sortKey(sort, SortKey.NAME, true);

            Page<User> result = userRepository.findAll(
                    PeopleSpecifications.students(criteria, grants, includeDeleted, dynamic, sortKey, ascending(direction)),
                    pageRequest(page, size));

            List<Long> ids = result.getContent().stream().map(User::getId).toList();
            Map<Long, List<UserRoleAssignment>> assignments = assignmentsByUser(ids);
            Map<Long, StudentProfile> profiles = profilesByUser(ids);
            Map<Long, OfferSummary> offers = offerSummaries(ids);
            Set<Long> withResume = ids.isEmpty() ? Set.of() : documentRepository
                    .findPrimaryForStudents(ids, StudentDocumentType.RESUME).stream()
                    .map(StudentDocument::getStudentUserId).collect(Collectors.toSet());

            List<StudentRow> rows = new ArrayList<>(ids.size());
            for (User u : result.getContent()) {
                UserRoleAssignment a = pickStudentAssignment(assignments.getOrDefault(u.getId(), List.of()), grants);
                rows.add(toStudentRow(u, a, profiles.get(u.getId()),
                        offers.getOrDefault(u.getId(), OfferSummary.NONE), withResume.contains(u.getId())));
            }
            log.debug("People search (students): actorId={}, grants={}, criteria={}, sort={} {}, page={}, size={}, total={}",
                    actor.userId(), grants, criteria, sortKey, direction, page, size, result.getTotalElements());
            return toPage(rows, result);

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("People search (students) failed for actorId={}: {}", actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not search students. Please try again.");
        }
    }

    @Transactional(readOnly = true)
    public PeoplePage<StaffRow> searchStaff(UserPrincipal actor, StaffSearchCriteria criteria,
                                            int page, int size, String sort, String direction) {
        try {
            List<Grant> grants = requireGrants(actor);
            boolean includeDeleted = includeDeleted(actor, criteria.includeDeleted());
            if (criteria.roles() != null && criteria.roles().contains(RoleType.STUDENT)) {
                throw new AdminExceptions.InvalidRequestException("Students are searched on the Students tab.");
            }
            SortKey sortKey = sortKey(sort, SortKey.NAME, false);

            Page<User> result = userRepository.findAll(
                    PeopleSpecifications.staff(criteria, grants, includeDeleted, sortKey, ascending(direction)),
                    pageRequest(page, size));

            List<Long> ids = result.getContent().stream().map(User::getId).toList();
            Map<Long, List<UserRoleAssignment>> assignments = assignmentsByUser(ids);

            List<StaffRow> rows = new ArrayList<>(ids.size());
            for (User u : result.getContent()) {
                // Only the assignments this caller could have found the person through.
                List<AssignmentView> visible = assignments.getOrDefault(u.getId(), List.of()).stream()
                        .filter(a -> a.getRole() != RoleType.STUDENT && inGrant(a, grants))
                        .map(PeopleSearchService::toAssignmentView)
                        .toList();
                rows.add(new StaffRow(u.getId(), u.getFirstName(), u.getLastName(), u.getEmail(), u.getPhone(),
                        u.getStatus(), u.isDeleted(), instant(u.getLastLoginAt()), instant(u.getCreatedAt()), visible));
            }
            log.debug("People search (staff): actorId={}, grants={}, criteria={}, sort={} {}, page={}, size={}, total={}",
                    actor.userId(), grants, criteria, sortKey, direction, page, size, result.getTotalElements());
            return toPage(rows, result);

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("People search (staff) failed for actorId={}: {}", actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not search staff. Please try again.");
        }
    }

    // ================================================================ detail

    /**
     * Everything on record about one person, for anyone who can find them
     * (decided 2026-11-07: no tiering). Someone outside the caller's reach gets a
     * 404, not a 403, so ids cannot be probed to learn who exists.
     * Documents are not here: the portal fetches them through user-service, which
     * writes the ADMIN_VIEW access log this would otherwise skip.
     */
    @Transactional(readOnly = true)
    public PersonDetail detail(UserPrincipal actor, Long userId) {
        try {
            List<Grant> grants = requireGrants(actor);
            boolean includeDeleted = visibility.canIncludeDeleted(actor);
            if (userRepository.count(PeopleSpecifications.visible(userId, grants, includeDeleted)) == 0) {
                log.warn("People detail denied: actorId={} cannot see userId={}", actor.userId(), userId);
                throw new AdminExceptions.NotFoundException("Person", userId);
            }
            User u = userRepository.findById(userId)
                    .orElseThrow(() -> new AdminExceptions.NotFoundException("Person", userId));
            List<UserRoleAssignment> assignments = assignmentRepository.findAllForUsers(List.of(userId));

            Account account = new Account(u.getId(), u.getFirstName(), u.getLastName(), u.getEmail(), u.getPhone(),
                    instant(u.getDateOfBirth()), u.getAddress(), u.getStatus(), u.isDeleted(),
                    instant(u.getDeletedAt()), instant(u.getLastLoginAt()), instant(u.getCreatedAt()));

            boolean isStudent = assignments.stream().anyMatch(a -> a.getRole() == RoleType.STUDENT);
            StudentDetail student = isStudent ? studentDetail(userId) : null;

            log.debug("People detail: actorId={} viewed userId={} (student={})", actor.userId(), userId, isStudent);
            return new PersonDetail(account, assignments.stream().map(PeopleSearchService::toAssignmentView).toList(), student);

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("People detail failed for actorId={}, userId={}: {}", actor.userId(), userId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load this person. Please try again.");
        }
    }

    private StudentDetail studentDetail(Long userId) {
        StudentProfile sp = em.createQuery(
                        "select sp from StudentProfile sp where sp.user.id = :id", StudentProfile.class)
                .setParameter("id", userId)
                .getResultStream().findFirst().orElse(null);

        List<OfferView> offers = em.createQuery("""
                        select o from PlacementOffer o join fetch o.company
                         where o.student.id = :id and o.isDeleted = false
                         order by o.offerDate desc, o.id desc
                        """, PlacementOffer.class)
                .setParameter("id", userId)
                .getResultList().stream()
                .map(o -> new OfferView(o.getId(), o.getCompany().getName(), o.getRoleTitle(), o.getOfferType(),
                        o.getStatus(), o.getCtcAmount(), o.getCtcCurrency(), o.getOfferDate(), o.isCountsTowardPlacement()))
                .toList();

        List<ApplicationView> applications = em.createQuery("""
                        select a.posting.id, a.posting.title, a.posting.company.name, a.status, a.appliedAt
                          from JobApplication a
                         where a.student.id = :id and a.isDeleted = false
                         order by a.appliedAt desc
                        """, Object[].class)
                .setParameter("id", userId)
                .setMaxResults(DETAIL_APPLICATIONS)
                .getResultList().stream()
                .map(r -> new ApplicationView((Long) r[0], (String) r[1], (String) r[2], String.valueOf(r[3]), (Instant) r[4]))
                .toList();

        if (sp == null) {
            // Invited, never filled a profile: the offers and applications still stand.
            return new StudentDetail(null, null, null, null, null, null, List.of(), null, null, null, null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), offers, applications);
        }

        List<DynamicValueView> advanced = em.createQuery("""
                        select v from StudentDynamicFieldValue v join fetch v.fieldDefinition
                         where v.studentProfile.id = :id
                         order by v.fieldDefinition.label
                        """, StudentDynamicFieldValue.class)
                .setParameter("id", sp.getId())
                .getResultList().stream()
                .map(v -> new DynamicValueView(v.getFieldDefinition().getId(), v.getFieldDefinition().getFieldKey(),
                        v.getFieldDefinition().getLabel(), v.getFieldDefinition().getDataType(),
                        v.getFieldDefinition().isActive(), dynamicValue(v)))
                .toList();

        // The seven lazy collections load once each -- one person, not a loop.
        return new StudentDetail(
                sp.getPrn(), sp.getGraduationYear(), sp.getCgpa(), sp.getTenthPercentage(), sp.getTwelfthPercentage(),
                sp.getDiplomaPercentage(),
                Arrays.asList(sp.getSem1Gpa(), sp.getSem2Gpa(), sp.getSem3Gpa(), sp.getSem4Gpa(),
                        sp.getSem5Gpa(), sp.getSem6Gpa(), sp.getSem7Gpa(), sp.getSem8Gpa()),
                sp.getCoCubesScore(), sp.getActiveBacklogs(), sp.getDeadBacklogs(), sp.getTotalBacklogs(), sp.getGapYears(),
                List.copyOf(sp.getSkills()),
                sp.getCodingProfiles().stream().map(c -> new CodingView(c.getPlatform(),
                        c.getPlatform() == null ? null : c.getPlatform().label(), c.getUsername(), c.getProfileLink(), c.getRating())).toList(),
                sp.getProfessionalProfiles().stream().map(p -> new LinkView(p.getPlatform(), p.getProfileLink())).toList(),
                sp.getProjects().stream().map(p -> new ProjectView(p.getTitle(), p.getProjectLink(), p.getDescription(), p.getTechnologies())).toList(),
                sp.getWorkExperiences().stream().map(w -> new ExperienceView(w.getCompany(), w.getRole(), w.getStartDate(),
                        w.getEndDate(), w.isCurrent(), w.getDuration(), w.getDescription())).toList(),
                sp.getAchievements().stream().map(a -> new AchievementView(a.getTitle(), a.getDescription())).toList(),
                sp.getCertifications().stream().map(c -> new CertificationView(c.getName(), c.getIssuer(), c.getIssuedOn(),
                        c.getExpiresOn(), c.getCredentialUrl(), c.getVerificationStatus(), c.isScorable())).toList(),
                advanced, offers, applications);
    }

    private static Object dynamicValue(StudentDynamicFieldValue v) {
        return switch (v.getFieldDefinition().getDataType()) {
            case NUMBER -> v.getValueNumber();
            case DATE -> instant(v.getValueDate());
            case BOOLEAN -> v.getValueBoolean();
            case STRING, ENUM -> v.getValueString();
        };
    }

    // ================================================================ filter options

    /** Everything the filter bar needs in one call, already narrowed to the caller's scope. */
    @Transactional(readOnly = true)
    public PeopleFilterOptions filterOptions(UserPrincipal actor) {
        try {
            List<Grant> grants = requireGrants(actor);
            EnumSet<RoleType> visibleRoles = PeopleVisibility.visibleRoles(grants);
            boolean trustWide = grants.stream().anyMatch(Grant::trustWide);

            List<College> colleges = trustWide
                    ? em.createQuery("select c from College c join fetch c.trust where c.isDeleted = false order by c.name", College.class)
                        .getResultList()
                    : em.createQuery("select c from College c join fetch c.trust where c.isDeleted = false and c.id in :ids order by c.name", College.class)
                        .setParameter("ids", grants.stream().map(Grant::collegeId).collect(Collectors.toSet()))
                        .getResultList();
            List<Long> collegeIds = colleges.stream().map(College::getId).toList();

            List<Department> departments = collegeIds.isEmpty() ? List.of() : em.createQuery("""
                            select d from Department d join fetch d.degree
                             where d.isDeleted = false and d.college.id in :ids
                             order by d.name
                            """, Department.class)
                    .setParameter("ids", collegeIds)
                    .getResultList();
            // A dept-scoped viewer (HOD, FDC) sees only departments one of their grants reaches.
            if (!trustWide) {
                departments = departments.stream().filter(d -> grants.stream().anyMatch(g ->
                        g.collegeId().equals(d.getCollege().getId()) && (g.deptId() == null || g.deptId().equals(d.getId())))).toList();
            }
            Map<Long, List<Department>> deptsByCollege = departments.stream()
                    .collect(Collectors.groupingBy(d -> d.getCollege().getId(), LinkedHashMap::new, Collectors.toList()));

            List<CollegeOption> collegeOptions = new ArrayList<>(colleges.size());
            for (College c : colleges) {
                List<Department> ds = deptsByCollege.getOrDefault(c.getId(), List.of());
                if (!trustWide && ds.isEmpty()) {
                    continue;
                }
                Map<Long, Option> degrees = new LinkedHashMap<>();
                ds.forEach(d -> degrees.putIfAbsent(d.getDegree().getId(), new Option(d.getDegree().getId(), d.getDegree().getName())));
                collegeOptions.add(new CollegeOption(c.getId(), c.getName(), c.getCode(), c.getTrust().getName(),
                        ds.stream().map(d -> new DepartmentOption(d.getId(), d.getName(), d.getCode(),
                                d.getDegree().getId(), d.getDegree().getName())).toList(),
                        List.copyOf(degrees.values())));
            }

            boolean students = visibleRoles.contains(RoleType.STUDENT);
            List<Integer> batches = !students ? List.of() : em.createQuery(
                            "select distinct sp.graduationYear from StudentProfile sp where sp.graduationYear is not null order by sp.graduationYear desc",
                            Integer.class)
                    .getResultList();
            List<Option> companies = !students ? List.of() : em.createQuery(
                            "select c.id, c.name from Company c where c.isDeleted = false order by c.name", Object[].class)
                    .getResultList().stream().map(r -> new Option((Long) r[0], (String) r[1])).toList();
            List<DynamicFieldOption> dynamicFields = !students ? List.of() : em.createQuery(
                            "select d.id, d.fieldKey, d.label, d.dataType, d.validationRules from DynamicFieldDefinition d where d.isActive = true order by d.label",
                            Object[].class)
                    .getResultList().stream()
                    .map(r -> new DynamicFieldOption((Long) r[0], (String) r[1], (String) r[2], (DynamicFieldDataType) r[3], (String) r[4]))
                    .toList();
            List<String> skillSuggestions = !students ? List.of() : em.createQuery(
                            "select lower(s) from StudentProfile sp join sp.skills s group by lower(s) order by count(s) desc, lower(s)",
                            String.class)
                    .setMaxResults(SKILL_SUGGESTIONS)
                    .getResultList();

            Set<Long> grantColleges = grants.stream().map(Grant::collegeId).collect(Collectors.toSet());
            Set<Long> grantDepts = grants.stream().map(Grant::deptId).collect(Collectors.toSet());
            Long lockedCollege = !trustWide && grantColleges.size() == 1 ? grantColleges.iterator().next() : null;
            Long lockedDept = lockedCollege != null && grantDepts.size() == 1 ? grantDepts.iterator().next() : null;

            return new PeopleFilterOptions(
                    List.copyOf(visibleRoles), students,
                    visibleRoles.stream().anyMatch(r -> r != RoleType.STUDENT),
                    visibility.canIncludeDeleted(actor), lockedCollege, lockedDept,
                    collegeOptions, batches, companies, dynamicFields, skillSuggestions,
                    List.of(UserStatus.values()), List.of(CodingPlatform.values()),
                    List.of(CertificationVerificationStatus.values()), List.of(StudentDocumentType.values()),
                    List.of(OfferType.values()));

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("People filter options failed for actorId={}: {}", actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load the search filters. Please try again.");
        }
    }

    // ================================================================ guards and parsing

    private List<Grant> requireGrants(UserPrincipal actor) {
        List<Grant> grants = visibility.grantsFor(actor);
        if (grants.isEmpty()) {
            log.warn("People search denied: actorId={} roles={} can view no one", actor.userId(), actor.roles());
            throw new ForbiddenException("Your role cannot search people.");
        }
        return grants;
    }

    private boolean includeDeleted(UserPrincipal actor, Boolean requested) {
        if (!Boolean.TRUE.equals(requested)) {
            return false;
        }
        if (!visibility.canIncludeDeleted(actor)) {
            log.warn("People search: actorId={} asked for deleted people without IT_ADMIN", actor.userId());
            throw new ForbiddenException("Only the IT Admin can search deleted accounts.");
        }
        return true;
    }

    private static void validateStudentCriteria(StudentSearchCriteria c) {
        if (c.semester() != null && (c.semester() < 1 || c.semester() > 8)) {
            throw new AdminExceptions.InvalidRequestException("Semester must be between 1 and 8.");
        }
        if ((c.semester() == null) != (c.semesterGpaMin() == null)) {
            throw new AdminExceptions.InvalidRequestException("Pick both a semester and its minimum GPA.");
        }
        if (c.cgpaMin() != null && c.cgpaMax() != null && c.cgpaMin() > c.cgpaMax()) {
            throw new AdminExceptions.InvalidRequestException("Minimum CGPA is above the maximum.");
        }
    }

    /** "definitionId|OPERATOR|value", checked against the field's type so a bad combination is a 400, not a 500. */
    private List<DynamicCondition> parseDynamic(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_DYNAMIC_CONDITIONS) {
            throw new AdminExceptions.InvalidRequestException("At most " + MAX_DYNAMIC_CONDITIONS + " advanced-field conditions.");
        }
        List<Object[]> parts = new ArrayList<>(raw.size());
        Set<Long> ids = new HashSet<>();
        for (String s : raw) {
            String[] p = s == null ? new String[0] : s.split("\\|", 3);
            if (p.length != 3 || p[2].isBlank()) {
                throw new AdminExceptions.InvalidRequestException("Advanced-field condition '" + s + "' is incomplete.");
            }
            try {
                Long id = Long.valueOf(p[0].trim());
                parts.add(new Object[]{id, DynamicOperator.valueOf(p[1].trim().toUpperCase(Locale.ROOT)), p[2]});
                ids.add(id);
            } catch (IllegalArgumentException e) {
                throw new AdminExceptions.InvalidRequestException("Advanced-field condition '" + s + "' is not valid.");
            }
        }
        Map<Long, DynamicFieldDataType> types = em.createQuery(
                        "select d.id, d.dataType from DynamicFieldDefinition d where d.id in :ids and d.isActive = true", Object[].class)
                .setParameter("ids", ids)
                .getResultList().stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (DynamicFieldDataType) r[1]));

        List<DynamicCondition> out = new ArrayList<>(parts.size());
        for (Object[] p : parts) {
            Long id = (Long) p[0];
            DynamicOperator op = (DynamicOperator) p[1];
            String value = (String) p[2];
            DynamicFieldDataType type = types.get(id);
            if (type == null) {
                throw new AdminExceptions.InvalidRequestException("Advanced field " + id + " does not exist or is disabled.");
            }
            boolean fits = switch (type) {
                case STRING -> op == DynamicOperator.EQ || op == DynamicOperator.CONTAINS;
                case ENUM -> op == DynamicOperator.EQ || op == DynamicOperator.IN;
                case NUMBER, DATE -> op == DynamicOperator.EQ || op == DynamicOperator.GTE || op == DynamicOperator.LTE;
                case BOOLEAN -> op == DynamicOperator.EQ;
            };
            if (!fits) {
                throw new AdminExceptions.InvalidRequestException("Operator " + op + " does not apply to a " + type + " field.");
            }
            try {
                switch (type) {
                    case NUMBER -> new BigDecimal(value.trim());
                    case DATE -> LocalDate.parse(value.trim());
                    case BOOLEAN -> {
                        if (!value.trim().equalsIgnoreCase("true") && !value.trim().equalsIgnoreCase("false")) {
                            throw new IllegalArgumentException();
                        }
                    }
                    default -> { }
                }
            } catch (RuntimeException e) {
                throw new AdminExceptions.InvalidRequestException("'" + value + "' is not a valid " + type + " value.");
            }
            out.add(new DynamicCondition(id, type, op, value));
        }
        return out;
    }

    private static SortKey sortKey(String sort, SortKey fallback, boolean student) {
        if (sort == null || sort.isBlank()) {
            return fallback;
        }
        SortKey key;
        try {
            key = SortKey.valueOf(sort.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AdminExceptions.InvalidRequestException("Cannot sort by '" + sort + "'.");
        }
        if (!student && (key == SortKey.PRN || key == SortKey.CGPA || key == SortKey.BATCH)) {
            throw new AdminExceptions.InvalidRequestException("Cannot sort staff by '" + sort + "'.");
        }
        return key;
    }

    private static boolean ascending(String direction) {
        return direction == null || !direction.trim().equalsIgnoreCase("desc");
    }

    private static PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1) {
            throw new AdminExceptions.InvalidRequestException("Page must be 0 or more and size at least 1.");
        }
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)); // unsorted: the Specification orders
    }

    // ================================================================ batch hydration

    private Map<Long, List<UserRoleAssignment>> assignmentsByUser(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return assignmentRepository.findAllForUsers(ids).stream()
                .collect(Collectors.groupingBy(a -> a.getUser().getId()));
    }

    private Map<Long, StudentProfile> profilesByUser(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return em.createQuery("select sp from StudentProfile sp where sp.user.id in :ids", StudentProfile.class)
                .setParameter("ids", ids)
                .getResultList().stream()
                .collect(Collectors.toMap(sp -> sp.getUser().getId(), Function.identity()));
    }

    private record OfferSummary(int live, boolean placed, boolean pending, boolean internship, String latestCompany) {
        static final OfferSummary NONE = new OfferSummary(0, false, false, false, null);
    }

    /** Live offers for a page of students in one query, folded per student. */
    private Map<Long, OfferSummary> offerSummaries(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = em.createQuery("""
                        select o.student.id, o.status, o.offerType, o.company.name
                          from PlacementOffer o
                         where o.student.id in :ids
                           and o.isDeleted = false
                           and o.countsTowardPlacement = true
                           and o.status in :live
                         order by o.offerDate desc, o.id desc
                        """, Object[].class)
                .setParameter("ids", ids)
                .setParameter("live", ReapplicationRules.liveStatuses())
                .getResultList();
        Map<Long, OfferSummary> out = new HashMap<>();
        for (Object[] r : rows) {
            Long student = (Long) r[0];
            OfferStatus status = (OfferStatus) r[1];
            OfferType type = (OfferType) r[2];
            OfferSummary s = out.getOrDefault(student, OfferSummary.NONE);
            boolean placement = OfferTypes.grantsPlacement(type);
            out.put(student, new OfferSummary(
                    s.live() + 1,
                    s.placed() || (placement && (status == OfferStatus.ACCEPTED || status == OfferStatus.JOINED)),
                    s.pending() || (placement && status == OfferStatus.OFFERED),
                    s.internship() || OfferTypes.grantsInternship(type),
                    s.latestCompany() != null ? s.latestCompany() : (String) r[3]));
        }
        return out;
    }

    // ================================================================ mapping

    /** The STUDENT assignment the caller found this person through: in a grant, active first. */
    private static UserRoleAssignment pickStudentAssignment(List<UserRoleAssignment> all, List<Grant> grants) {
        UserRoleAssignment best = null;
        for (UserRoleAssignment a : all) {
            if (a.getRole() != RoleType.STUDENT || !inGrant(a, grants)) {
                continue;
            }
            if (best == null || (a.isActive() && !best.isActive())) {
                best = a;
            }
        }
        return best;
    }

    /** Java mirror of the grant predicate in PeopleSpecifications.assignmentExists. */
    static boolean inGrant(UserRoleAssignment a, List<Grant> grants) {
        for (Grant g : grants) {
            if (!g.roles().contains(a.getRole())) {
                continue;
            }
            if (!g.trustWide() && !g.collegeId().equals(a.getCollegeId())) {
                continue;
            }
            if (g.deptId() != null && !g.deptId().equals(a.getDeptId())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static StudentRow toStudentRow(User u, UserRoleAssignment a, StudentProfile sp, OfferSummary offers, boolean resume) {
        PlacementFilter placement = offers.placed() ? PlacementFilter.PLACED
                : offers.pending() ? PlacementFilter.OFFER_PENDING
                : PlacementFilter.UNPLACED;
        return new StudentRow(u.getId(), u.getFirstName(), u.getLastName(), u.getEmail(), u.getPhone(),
                sp == null ? null : sp.getPrn(), u.getStatus(), u.isDeleted(), a != null && a.isActive(),
                a == null || a.getCollege() == null ? null : a.getCollege().getName(),
                a == null || a.getCollege() == null ? null : a.getCollege().getCode(),
                a == null || a.getDepartment() == null ? null : a.getDepartment().getName(),
                a == null || a.getDegree() == null ? null : a.getDegree().getName(),
                sp == null ? null : sp.getGraduationYear(), sp == null ? null : sp.getCgpa(),
                sp == null ? null : sp.getActiveBacklogs(),
                placement, offers.live(), offers.internship(), offers.latestCompany(), resume,
                instant(u.getLastLoginAt()));
    }

    static AssignmentView toAssignmentView(UserRoleAssignment a) {
        College c = a.getCollege();
        Department d = a.getDepartment();
        return new AssignmentView(a.getId(), a.getRole(),
                c == null ? null : c.getId(), c == null ? null : c.getName(), c == null ? null : c.getCode(),
                c == null ? null : c.getTrust().getName(),
                d == null ? null : d.getId(), d == null ? null : d.getName(),
                a.getDegree() == null ? null : a.getDegree().getId(),
                a.getDegree() == null ? null : a.getDegree().getName(),
                a.isActive(), instant(a.getAssignedAt()), instant(a.getRevokedAt()));
    }

    private static <T> PeoplePage<T> toPage(List<T> rows, Page<User> result) {
        return new PeoplePage<>(rows, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isLast());
    }

    // Via epoch millis, not Date.toInstant(): a column Hibernate hands back as
    // java.sql.Date throws UnsupportedOperationException from toInstant().
    private static Instant instant(Date date) {
        return date == null ? null : Instant.ofEpochMilli(date.getTime());
    }
}

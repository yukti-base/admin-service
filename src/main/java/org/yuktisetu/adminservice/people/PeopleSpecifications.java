package org.yuktisetu.adminservice.people;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.yuktisetu.adminservice.people.PeopleDtos.DynamicCondition;
import org.yuktisetu.adminservice.people.PeopleDtos.PersonStatus;
import org.yuktisetu.adminservice.people.PeopleDtos.PlacementFilter;
import org.yuktisetu.adminservice.people.PeopleDtos.SkillMatch;
import org.yuktisetu.adminservice.people.PeopleDtos.StaffSearchCriteria;
import org.yuktisetu.adminservice.people.PeopleDtos.StudentSearchCriteria;
import org.yuktisetu.adminservice.people.PeopleVisibility.Grant;
import org.yuktisetu.drive.db.PlacementOffer;
import org.yuktisetu.drive.model.OfferStatus;
import org.yuktisetu.drive.model.OfferType;
import org.yuktisetu.drive.model.OfferTypes;
import org.yuktisetu.drive.model.ReapplicationRules;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.db.UserRoleAssignment;
import org.yuktisetu.identity.model.RoleType;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.profile.db.Certification;
import org.yuktisetu.profile.db.CodingProfile;
import org.yuktisetu.profile.db.StudentDocument;
import org.yuktisetu.profile.db.StudentDynamicFieldValue;
import org.yuktisetu.profile.db.StudentProfile;
import org.yuktisetu.profile.db.WorkExperience;
import org.yuktisetu.profile.model.CertificationVerificationStatus;
import org.yuktisetu.profile.model.StudentDocumentType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The people search, as JPA Specifications over {@link User}.
 *
 * Shape of the SQL, and why:
 * <ul>
 *   <li><b>Scope and the caller's role/college/department/degree filters live in ONE
 *   {@code EXISTS} on user_role_assignments.</b> Binding them to the same row is
 *   what makes them correct: a student who holds a revoked PCCOE assignment and an
 *   active Akurdi one must not match "college = PCCOE, status = active" by taking
 *   the college from one row and the status from the other. The unique index
 *   (user_id, role, college_id, dept_id) serves the probe.</li>
 *   <li><b>Every child-table condition is its own {@code EXISTS}</b> (skills, coding,
 *   experience, certifications, documents, offers, advanced fields), so a person
 *   matches at most once -- no DISTINCT, no row multiplication, and the count query
 *   stays a plain count.</li>
 *   <li><b>All scalar profile conditions share one {@code EXISTS}</b> on
 *   student_profiles (unique on user_id).</li>
 *   <li><b>Ordering is applied here, not through Pageable</b>, because CGPA, PRN and
 *   batch sit on student_profiles, which {@code User} does not map. The data query
 *   gets a LEFT entity join for them; the count query never sorts.</li>
 * </ul>
 * A null criterion adds nothing. A comparison on a column the student has not
 * filled (null CGPA, null backlogs) does not match: unknown data never satisfies
 * a filter.
 */
public final class PeopleSpecifications {

    private PeopleSpecifications() {}

    /** Calendar dates in the filters are Indian dates, as everywhere else in the drive rules. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final Set<UserStatus> DEACTIVATED_ACCOUNT =
            EnumSet.of(UserStatus.INACTIVE, UserStatus.SUSPENDED, UserStatus.LOCKED);

    private static final List<OfferType> PLACEMENT_TYPES =
            Arrays.stream(OfferType.values()).filter(OfferTypes::grantsPlacement).toList();
    private static final List<OfferType> INTERNSHIP_TYPES =
            Arrays.stream(OfferType.values()).filter(OfferTypes::grantsInternship).toList();
    private static final List<OfferStatus> ACCEPTED = List.of(OfferStatus.ACCEPTED, OfferStatus.JOINED);
    private static final List<CertificationVerificationStatus> SCORABLE_CERTS =
            Arrays.stream(CertificationVerificationStatus.values())
                    .filter(CertificationVerificationStatus::countsTowardScore).toList();

    /** Sort keys the caller may ask for. Anything else is a 400, never a raw column name. */
    public enum SortKey { NAME, EMAIL, CREATED_AT, LAST_LOGIN_AT, PRN, CGPA, BATCH }

    // ================================================================ entry points

    public static Specification<User> students(StudentSearchCriteria c, List<Grant> grants, boolean includeDeleted,
                                               List<DynamicCondition> dynamic, SortKey sort, boolean ascending) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(assignmentExists(root, query, cb, grants, EnumSet.of(RoleType.STUDENT),
                    c.collegeIds(), c.departmentIds(), c.degreeIds(), statusOrDefault(c.status()), null, null));
            p.addAll(accountPredicates(root, cb, statusOrDefault(c.status()), c.accountStatuses(), includeDeleted,
                    c.neverLoggedIn(), c.createdFrom(), c.createdTo(), null));
            addIfPresent(p, textSearch(root, query, cb, c.q(), true));
            addIfPresent(p, profileScalars(root, query, cb, c));
            p.addAll(skills(root, query, cb, c.skills(), c.skillMatch()));
            addIfPresent(p, codingPlatforms(root, query, cb, c));
            p.addAll(experience(root, query, cb, c.hasWorkExperience(), c.currentlyInterning()));
            p.addAll(certifications(root, query, cb, c.hasScorableCertification(), c.certificationStatuses()));
            p.addAll(documents(root, query, cb, c.hasPrimaryResume(), c.missingDocTypes()));
            p.addAll(placement(root, query, cb, c));
            for (DynamicCondition d : dynamic) {
                p.add(dynamicField(root, query, cb, d));
            }
            applyOrder(root, query, cb, sort, ascending, true);
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    public static Specification<User> staff(StaffSearchCriteria c, List<Grant> grants, boolean includeDeleted,
                                            SortKey sort, boolean ascending) {
        return (root, query, cb) -> {
            EnumSet<RoleType> roles = EnumSet.complementOf(EnumSet.of(RoleType.STUDENT));
            if (c.roles() != null && !c.roles().isEmpty()) {
                roles.retainAll(c.roles());
            }
            List<Predicate> p = new ArrayList<>();
            PersonStatus status = statusOrDefault(c.status());
            p.add(assignmentExists(root, query, cb, grants, roles, c.collegeIds(), c.departmentIds(), null,
                    status, c.assignedFrom(), c.assignedTo()));
            p.addAll(accountPredicates(root, cb, status, c.accountStatuses(), includeDeleted,
                    c.neverLoggedIn(), null, null, c.lastLoginBefore()));
            addIfPresent(p, textSearch(root, query, cb, c.q(), false));
            applyOrder(root, query, cb, sort, ascending, false);
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    /**
     * Whether one person is visible to the caller at all, whatever their status --
     * the detail view's check. The same scope predicate as the search, so the two
     * cannot disagree.
     */
    public static Specification<User> visible(Long userId, List<Grant> grants, boolean includeDeleted) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("id"), userId));
            p.add(assignmentExists(root, query, cb, grants, EnumSet.allOf(RoleType.class),
                    null, null, null, PersonStatus.ANY, null, null));
            if (!includeDeleted) {
                p.add(cb.isFalse(root.get("isDeleted")));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    // ================================================================ scope + assignment

    private static Predicate assignmentExists(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                              List<Grant> grants, Set<RoleType> roles,
                                              Collection<Long> collegeIds, Collection<Long> departmentIds,
                                              Collection<Long> degreeIds, PersonStatus status,
                                              LocalDate assignedFrom, LocalDate assignedTo) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<UserRoleAssignment> a = sub.from(UserRoleAssignment.class);
        List<Predicate> p = new ArrayList<>();
        p.add(cb.equal(a.get("user"), root));

        // The OR of the caller's grants, each narrowed to the roles this search is about.
        List<Predicate> scopes = new ArrayList<>();
        for (Grant g : grants) {
            EnumSet<RoleType> reach = EnumSet.noneOf(RoleType.class);
            reach.addAll(g.roles());
            reach.retainAll(roles);
            if (reach.isEmpty()) {
                continue;
            }
            List<Predicate> s = new ArrayList<>();
            s.add(a.get("role").in(reach));
            if (!g.trustWide()) {
                s.add(cb.equal(a.get("college").get("id"), g.collegeId()));
            }
            if (g.deptId() != null) {
                s.add(cb.equal(a.get("department").get("id"), g.deptId()));
            }
            scopes.add(cb.and(s.toArray(Predicate[]::new)));
        }
        // No grant reaches these roles: an always-false predicate rather than "no scope".
        p.add(scopes.isEmpty() ? cb.disjunction() : cb.or(scopes.toArray(Predicate[]::new)));

        if (notEmpty(collegeIds)) {
            p.add(a.get("college").get("id").in(collegeIds));
        }
        if (notEmpty(departmentIds)) {
            p.add(a.get("department").get("id").in(departmentIds));
        }
        if (notEmpty(degreeIds)) {
            p.add(a.get("degree").get("id").in(degreeIds));
        }
        switch (status) {
            case ACTIVE, PENDING_INVITE -> p.add(cb.isTrue(a.get("isActive")));
            case DEACTIVATED -> p.add(cb.or(cb.isFalse(a.get("isActive")), root.get("status").in(DEACTIVATED_ACCOUNT)));
            case ANY -> { /* revoked assignments still place a person in scope */ }
        }
        if (assignedFrom != null) {
            p.add(cb.greaterThanOrEqualTo(a.get("assignedAt"), startOf(assignedFrom)));
        }
        if (assignedTo != null) {
            p.add(cb.lessThan(a.get("assignedAt"), startOf(assignedTo.plusDays(1))));
        }
        sub.select(cb.literal(1)).where(p.toArray(Predicate[]::new));
        return cb.exists(sub);
    }

    // ================================================================ account

    private static List<Predicate> accountPredicates(Root<User> root, CriteriaBuilder cb, PersonStatus status,
                                                     Collection<UserStatus> accountStatuses, boolean includeDeleted,
                                                     Boolean neverLoggedIn, LocalDate createdFrom, LocalDate createdTo,
                                                     LocalDate lastLoginBefore) {
        List<Predicate> p = new ArrayList<>();
        switch (status) {
            case ACTIVE -> p.add(cb.equal(root.get("status"), UserStatus.ACTIVE));
            case PENDING_INVITE -> p.add(cb.equal(root.get("status"), UserStatus.PENDING_ACTIVATION));
            case DEACTIVATED, ANY -> { /* handled in the assignment EXISTS, or no condition */ }
        }
        if (notEmpty(accountStatuses)) {
            p.add(root.get("status").in(accountStatuses));
        }
        if (!includeDeleted) {
            p.add(cb.isFalse(root.get("isDeleted")));
        }
        if (neverLoggedIn != null) {
            p.add(neverLoggedIn ? cb.isNull(root.get("lastLoginAt")) : cb.isNotNull(root.get("lastLoginAt")));
        }
        if (createdFrom != null) {
            p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startOf(createdFrom)));
        }
        if (createdTo != null) {
            p.add(cb.lessThan(root.get("createdAt"), startOf(createdTo.plusDays(1))));
        }
        if (lastLoginBefore != null) {
            p.add(cb.lessThan(root.get("lastLoginAt"), startOf(lastLoginBefore)));
        }
        return p;
    }

    // ================================================================ text

    /**
     * Every word must appear in at least one of name, email, phone (and PRN for
     * students), case-insensitively. "riya comp" finds Riya whose email has "comp".
     * Served by the pg_trgm indexes in 2026-11-07-people-search-indexes.sql.
     */
    private static Predicate textSearch(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                        String q, boolean withPrn) {
        List<String> tokens = tokens(q);
        if (tokens.isEmpty()) {
            return null;
        }
        List<Predicate> all = new ArrayList<>();
        for (String token : tokens) {
            String pattern = "%" + escapeLike(token) + "%";
            List<Predicate> any = new ArrayList<>();
            any.add(cb.like(cb.lower(root.get("firstName")), pattern, '\\'));
            any.add(cb.like(cb.lower(root.get("lastName")), pattern, '\\'));
            any.add(cb.like(cb.lower(root.get("email")), pattern, '\\'));
            any.add(cb.like(root.get("phone"), pattern, '\\'));
            if (withPrn) {
                Subquery<Integer> sub = query.subquery(Integer.class);
                Root<StudentProfile> sp = sub.from(StudentProfile.class);
                sub.select(cb.literal(1)).where(cb.equal(sp.get("user"), root),
                        cb.like(cb.lower(sp.get("prn")), pattern, '\\'));
                any.add(cb.exists(sub));
            }
            all.add(cb.or(any.toArray(Predicate[]::new)));
        }
        return cb.and(all.toArray(Predicate[]::new));
    }

    static List<String> tokens(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        return Arrays.stream(q.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(t -> !t.isEmpty())
                .distinct()
                .limit(6)
                .toList();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ================================================================ student profile

    private static Predicate profileScalars(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                            StudentSearchCriteria c) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<StudentProfile> sp = sub.from(StudentProfile.class);
        List<Predicate> p = new ArrayList<>();
        if (notEmpty(c.batches())) {
            p.add(sp.get("graduationYear").in(c.batches()));
        }
        ge(cb, p, sp.get("cgpa"), c.cgpaMin());
        le(cb, p, sp.get("cgpa"), c.cgpaMax());
        ge(cb, p, sp.get("tenthPercentage"), c.tenthMin());
        ge(cb, p, sp.get("twelfthPercentage"), c.twelfthMin());
        ge(cb, p, sp.get("diplomaPercentage"), c.diplomaMin());
        ge(cb, p, sp.get("coCubesScore"), c.coCubesMin());
        if (c.semester() != null && c.semesterGpaMin() != null) {
            ge(cb, p, sp.get("sem" + c.semester() + "Gpa"), c.semesterGpaMin()); // semester is validated 1..8
        }
        le(cb, p, sp.get("activeBacklogs"), c.activeBacklogsMax());
        le(cb, p, sp.get("deadBacklogs"), c.deadBacklogsMax());
        le(cb, p, sp.get("totalBacklogs"), c.totalBacklogsMax());
        le(cb, p, sp.get("gapYears"), c.gapYearsMax());
        if (p.isEmpty()) {
            return null;
        }
        p.add(cb.equal(sp.get("user"), root));
        sub.select(cb.literal(1)).where(p.toArray(Predicate[]::new));
        return cb.exists(sub);
    }

    /** Skills are tags: matched whole and case-insensitively, so "java" does not match "javascript". */
    private static List<Predicate> skills(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                          List<String> skills, SkillMatch match) {
        List<String> wanted = skills == null ? List.of() : skills.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .distinct().toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        if (match == SkillMatch.ALL) {
            return wanted.stream().map(s -> skillExists(root, query, cb, List.of(s))).toList();
        }
        return List.of(skillExists(root, query, cb, wanted));
    }

    private static Predicate skillExists(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb, List<String> anyOf) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<StudentProfile> sp = sub.from(StudentProfile.class);
        Join<StudentProfile, String> skill = sp.join("skills");
        sub.select(cb.literal(1)).where(cb.equal(sp.get("user"), root), cb.lower(skill).in(anyOf));
        return cb.exists(sub);
    }

    private static Predicate codingPlatforms(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                             StudentSearchCriteria c) {
        if (!notEmpty(c.codingPlatforms())) {
            return null;
        }
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<CodingProfile> cp = sub.from(CodingProfile.class);
        sub.select(cb.literal(1)).where(cb.equal(cp.get("studentProfile").get("user"), root),
                cp.get("platform").in(c.codingPlatforms()),
                cb.isNotNull(cp.get("username")));
        return cb.exists(sub);
    }

    private static List<Predicate> experience(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                              Boolean hasExperience, Boolean currentlyInterning) {
        List<Predicate> p = new ArrayList<>();
        if (hasExperience != null) {
            Predicate exists = workExists(root, query, cb, false);
            p.add(hasExperience ? exists : cb.not(exists));
        }
        if (currentlyInterning != null) {
            Predicate exists = workExists(root, query, cb, true);
            p.add(currentlyInterning ? exists : cb.not(exists));
        }
        return p;
    }

    private static Predicate workExists(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb, boolean currentOnly) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<WorkExperience> w = sub.from(WorkExperience.class);
        List<Predicate> p = new ArrayList<>();
        p.add(cb.equal(w.get("studentProfile").get("user"), root));
        if (currentOnly) {
            p.add(cb.isTrue(w.get("isCurrent")));
        }
        sub.select(cb.literal(1)).where(p.toArray(Predicate[]::new));
        return cb.exists(sub);
    }

    private static List<Predicate> certifications(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                                  Boolean hasScorable, List<CertificationVerificationStatus> statuses) {
        List<Predicate> p = new ArrayList<>();
        if (hasScorable != null) {
            Predicate exists = certExists(root, query, cb, SCORABLE_CERTS);
            p.add(hasScorable ? exists : cb.not(exists));
        }
        if (notEmpty(statuses)) {
            p.add(certExists(root, query, cb, statuses));
        }
        return p;
    }

    private static Predicate certExists(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                        Collection<CertificationVerificationStatus> statuses) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<Certification> cert = sub.from(Certification.class);
        sub.select(cb.literal(1)).where(cb.equal(cert.get("studentProfile").get("user"), root),
                cert.get("verificationStatus").in(statuses));
        return cb.exists(sub);
    }

    private static List<Predicate> documents(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                             Boolean hasPrimaryResume, List<StudentDocumentType> missing) {
        List<Predicate> p = new ArrayList<>();
        if (hasPrimaryResume != null) {
            Subquery<Integer> sub = query.subquery(Integer.class);
            Root<StudentDocument> d = sub.from(StudentDocument.class);
            sub.select(cb.literal(1)).where(cb.equal(d.get("student"), root),
                    cb.equal(d.get("docType"), StudentDocumentType.RESUME),
                    cb.isTrue(d.get("isPrimary")),
                    cb.isFalse(d.get("isDeleted")));
            p.add(hasPrimaryResume ? cb.exists(sub) : cb.not(cb.exists(sub)));
        }
        if (notEmpty(missing)) {
            for (StudentDocumentType type : EnumSet.copyOf(missing)) {
                Subquery<Integer> sub = query.subquery(Integer.class);
                Root<StudentDocument> d = sub.from(StudentDocument.class);
                sub.select(cb.literal(1)).where(cb.equal(d.get("student"), root),
                        cb.equal(d.get("docType"), type),
                        cb.isFalse(d.get("isDeleted")));
                p.add(cb.not(cb.exists(sub)));
            }
        }
        return p;
    }

    // ================================================================ placement

    /**
     * Derived from live offers (OFFERED, ACCEPTED, JOINED -- ReapplicationRules'
     * definition), counting only offers that count toward placement, classified
     * through OfferTypes so this agrees with the reapplication rules:
     * <ul>
     *   <li>PLACED: an ACCEPTED or JOINED placement offer.</li>
     *   <li>OFFER_PENDING: an OFFERED placement offer, and not placed.</li>
     *   <li>UNPLACED: no live placement offer at all.</li>
     *   <li>HOLDS_INTERNSHIP: a live internship offer.</li>
     * </ul>
     */
    private static List<Predicate> placement(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                             StudentSearchCriteria c) {
        List<Predicate> p = new ArrayList<>();
        List<OfferStatus> live = ReapplicationRules.liveStatuses();
        if (c.placement() != null) {
            switch (c.placement()) {
                case PLACED -> p.add(offerExists(root, query, cb, ACCEPTED, PLACEMENT_TYPES, null));
                case OFFER_PENDING -> {
                    p.add(offerExists(root, query, cb, List.of(OfferStatus.OFFERED), PLACEMENT_TYPES, null));
                    p.add(cb.not(offerExists(root, query, cb, ACCEPTED, PLACEMENT_TYPES, null)));
                }
                case UNPLACED -> p.add(cb.not(offerExists(root, query, cb, live, PLACEMENT_TYPES, null)));
                case HOLDS_INTERNSHIP -> p.add(offerExists(root, query, cb, live, INTERNSHIP_TYPES, null));
            }
        }
        if (notEmpty(c.offerCompanyIds())) {
            p.add(offerExists(root, query, cb, live, null, c.offerCompanyIds()));
        }
        if (notEmpty(c.offerTypes())) {
            p.add(offerExists(root, query, cb, live, c.offerTypes(), null));
        }
        if (c.offerCountMin() != null && c.offerCountMin() > 0) {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<PlacementOffer> o = sub.from(PlacementOffer.class);
            sub.select(cb.count(o)).where(liveOffer(cb, o, root, live));
            p.add(cb.greaterThanOrEqualTo(sub, c.offerCountMin().longValue()));
        }
        return p;
    }

    private static Predicate offerExists(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                         Collection<OfferStatus> statuses, Collection<OfferType> types,
                                         Collection<Long> companyIds) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<PlacementOffer> o = sub.from(PlacementOffer.class);
        List<Predicate> p = new ArrayList<>(List.of(liveOffer(cb, o, root, statuses)));
        if (types != null) {
            p.add(o.get("offerType").in(types));
        }
        if (companyIds != null) {
            p.add(o.get("company").get("id").in(companyIds));
        }
        sub.select(cb.literal(1)).where(p.toArray(Predicate[]::new));
        return cb.exists(sub);
    }

    private static Predicate[] liveOffer(CriteriaBuilder cb, Root<PlacementOffer> o, Root<User> student,
                                         Collection<OfferStatus> statuses) {
        return new Predicate[]{
                cb.equal(o.get("student"), student),
                cb.isFalse(o.get("isDeleted")),
                cb.isTrue(o.get("countsTowardPlacement")),
                o.get("status").in(statuses)};
    }

    // ================================================================ advanced fields

    /** One admin-defined field condition. The service has already checked the operator fits the type. */
    private static Predicate dynamicField(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb, DynamicCondition d) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<StudentDynamicFieldValue> v = sub.from(StudentDynamicFieldValue.class);
        List<Predicate> p = new ArrayList<>();
        p.add(cb.equal(v.get("studentProfile").get("user"), root));
        p.add(cb.equal(v.get("fieldDefinition").get("id"), d.definitionId()));
        String raw = d.value().trim();
        switch (d.dataType()) {
            case STRING, ENUM -> {
                Expression<String> value = cb.lower(v.get("valueString"));
                switch (d.operator()) {
                    case CONTAINS -> p.add(cb.like(value, "%" + escapeLike(raw.toLowerCase(Locale.ROOT)) + "%", '\\'));
                    case IN -> p.add(value.in(Arrays.stream(raw.split(","))
                            .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList()));
                    default -> p.add(cb.equal(value, raw.toLowerCase(Locale.ROOT)));
                }
            }
            case NUMBER -> compare(cb, p, v.get("valueNumber"), new BigDecimal(raw), d.operator());
            case DATE -> compare(cb, p, v.get("valueDate"), startOf(LocalDate.parse(raw)), d.operator());
            case BOOLEAN -> p.add(cb.equal(v.get("valueBoolean"), Boolean.parseBoolean(raw)));
        }
        sub.select(cb.literal(1)).where(p.toArray(Predicate[]::new));
        return cb.exists(sub);
    }

    private static <Y extends Comparable<? super Y>> void compare(CriteriaBuilder cb, List<Predicate> p, Path<Y> path,
                                                                 Y value, PeopleDtos.DynamicOperator op) {
        switch (op) {
            case GTE -> p.add(cb.greaterThanOrEqualTo(path, value));
            case LTE -> p.add(cb.lessThanOrEqualTo(path, value));
            default -> p.add(cb.equal(path, value));
        }
    }

    // ================================================================ ordering

    /**
     * Applied to the data query only -- a count query must not sort, and Spring
     * Data runs the same Specification for both. id is always the last key, so
     * paging is stable when names tie.
     */
    private static void applyOrder(Root<User> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                   SortKey sort, boolean ascending, boolean student) {
        Class<?> resultType = query.getResultType();
        if (resultType == Long.class || resultType == long.class) {
            return;
        }
        List<Expression<?>> keys = new ArrayList<>();
        switch (sort) {
            case NAME -> {
                keys.add(cb.lower(root.get("firstName")));
                keys.add(cb.lower(root.get("lastName")));
            }
            case EMAIL -> keys.add(cb.lower(root.get("email")));
            case CREATED_AT -> keys.add(root.get("createdAt"));
            case LAST_LOGIN_AT -> keys.add(root.get("lastLoginAt"));
            case PRN, CGPA, BATCH -> {
                if (!student) {
                    throw new IllegalArgumentException("Sort " + sort + " applies to students only");
                }
                Join<User, StudentProfile> sp = root.join(StudentProfile.class, JoinType.LEFT);
                sp.on(cb.equal(sp.get("user"), root));
                keys.add(switch (sort) {
                    case PRN -> sp.get("prn");
                    case CGPA -> sp.get("cgpa");
                    default -> sp.get("graduationYear");
                });
            }
        }
        // Nulls last in both directions: Postgres puts them FIRST under DESC, so
        // "highest CGPA" opened on every student who has not filled one in.
        List<Order> orders = new ArrayList<>();
        for (Expression<?> key : keys) {
            orders.add(ascending ? cb.asc(key, Nulls.LAST) : cb.desc(key, Nulls.LAST));
        }
        orders.add(cb.asc(root.get("id")));
        query.orderBy(orders);
    }

    // ================================================================ helpers

    private static PersonStatus statusOrDefault(PersonStatus status) {
        return status == null ? PersonStatus.ACTIVE : status;
    }

    private static void addIfPresent(List<Predicate> p, Predicate predicate) {
        if (predicate != null) {
            p.add(predicate);
        }
    }

    private static void ge(CriteriaBuilder cb, List<Predicate> p, Path<Double> path, Double min) {
        if (min != null) {
            p.add(cb.greaterThanOrEqualTo(path, min));
        }
    }

    private static <N extends Number & Comparable<? super N>> void le(CriteriaBuilder cb, List<Predicate> p,
                                                                     Path<N> path, N max) {
        if (max != null) {
            p.add(cb.lessThanOrEqualTo(path, max));
        }
    }

    private static boolean notEmpty(Collection<?> c) {
        return c != null && !c.isEmpty();
    }

    private static Date startOf(LocalDate date) {
        return Date.from(date.atStartOfDay(IST).toInstant());
    }
}

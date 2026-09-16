package org.yuktisetu.adminservice.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yuktisetu.core.security.UserPrincipal;

import java.util.Collection;

/**
 * Authorization rules for company onboarding and drive posting.
 *
 * Same shape as InstituteHierarchyPolicy: no dependencies, boolean methods,
 * denial logged. The controller's @PreAuthorize is a coarse gate; this class is
 * the actual authority.
 *
 * Two rules come straight from the TnP Cell:
 *
 *  - Posting authority runs from TNP_SUPER_ADMIN down to TNP_COORDINATOR
 *    inclusive. HoD and below cannot post a drive.
 *  - A college-scoped actor may only target colleges they actually hold an
 *    assignment for. Targeting a peer college requires a trust-wide role. This
 *    is the boundary that stops a PCCOE coordinator publishing a drive that
 *    notifies and binds students at another PCET college.
 *
 * Blacklisting and hard delete are deliberately tighter than day-to-day
 * management, matching the pattern InstituteHierarchyPolicy already set for
 * restore and hard delete.
 */
@Slf4j
@Component
public class PlacementDrivePolicy {

    private static final String IT_ADMIN = "IT_ADMIN";
    private static final String TNP_SUPER_ADMIN = "TNP_SUPER_ADMIN";
    private static final String TNP_COLLEGE_ADMIN = "TNP_COLLEGE_ADMIN";
    private static final String TNP_COORDINATOR = "TNP_COORDINATOR";
    private static final String HOD = "HOD";
    private static final String FACULTY_DEPT_COORDINATOR = "FACULTY_DEPT_COORDINATOR";

    /** Trust-wide roles reach every college by definition. */
    private boolean isTrustWide(UserPrincipal actor) {
        return actor.hasRole(IT_ADMIN) || actor.hasRole(TNP_SUPER_ADMIN);
    }

    /**
     * Companies are trust-wide records, so onboarding one is a trust-level act in
     * effect even when a college admin does it. Anyone who may post a drive may
     * also onboard the company they are posting for -- blocking that would just
     * make coordinators wait on a Super Admin to type in a name.
     */
    public boolean canManageCompany(UserPrincipal actor) {
        boolean allowed = isTrustWide(actor)
                || actor.hasRole(TNP_COLLEGE_ADMIN)
                || actor.hasRole(TNP_COORDINATOR);
        if (!allowed) {
            log.warn("Company-management action denied for actorId={} roles={}", actor.userId(), actor.roles());
        }
        return allowed;
    }

    /**
     * Blacklisting cuts off a recruiter for the whole trust, including colleges
     * the actor has nothing to do with. Trust-level only.
     */
    public boolean canBlacklistCompany(UserPrincipal actor) {
        boolean allowed = isTrustWide(actor);
        if (!allowed) {
            log.warn("Company blacklist denied for actorId={} roles={}", actor.userId(), actor.roles());
        }
        return allowed;
    }

    public boolean canRestoreCompany(UserPrincipal actor) {
        boolean allowed = isTrustWide(actor);
        if (!allowed) {
            log.warn("Company restore denied for actorId={} roles={}", actor.userId(), actor.roles());
        }
        return allowed;
    }

    public boolean canHardDelete(UserPrincipal actor) {
        boolean allowed = actor.hasRole(IT_ADMIN);
        if (!allowed) {
            log.warn("Drive hard-delete denied for actorId={} roles={}", actor.userId(), actor.roles());
        }
        return allowed;
    }

    /** Authoring a draft. Same role band as publishing; the scope check bites at publish. */
    public boolean canDraftPosting(UserPrincipal actor) {
        boolean allowed = isTrustWide(actor)
                || actor.hasRole(TNP_COLLEGE_ADMIN)
                || actor.hasRole(TNP_COORDINATOR);
        if (!allowed) {
            log.warn("Posting authoring denied for actorId={} roles={}", actor.userId(), actor.roles());
        }
        return allowed;
    }

    /**
     * The one that matters for multi-college drives.
     *
     * A trust-wide actor may publish anywhere. A college-scoped actor must hold a
     * TNP_COLLEGE_ADMIN or TNP_COORDINATOR assignment for EVERY college in the
     * target set -- not merely one of them. Checking "any" instead of "every"
     * would let a coordinator at one college attach a second college to the
     * targets and publish into it.
     */
    public boolean canPublishPosting(UserPrincipal actor, Collection<Long> targetCollegeIds) {
        if (isTrustWide(actor)) {
            return true;
        }
        if (targetCollegeIds == null || targetCollegeIds.isEmpty()) {
            log.warn("Publish denied for actorId={}: posting has no targets", actor.userId());
            return false;
        }
        boolean allowed = targetCollegeIds.stream().allMatch(collegeId ->
                actor.hasRoleForCollege(TNP_COLLEGE_ADMIN, collegeId)
                        || actor.hasRoleForCollege(TNP_COORDINATOR, collegeId));
        if (!allowed) {
            log.warn("Publish denied for actorId={} roles={}: targets {} include a college outside the actor's scope",
                    actor.userId(), actor.roles(), targetCollegeIds);
        }
        return allowed;
    }

    /** Closing early or cancelling a live drive, with recruiters already informed. */
    public boolean canCancelPosting(UserPrincipal actor, Collection<Long> targetCollegeIds) {
        return canPublishPosting(actor, targetCollegeIds);
    }

    /**
     * Reading a drive's applicant pool.
     *
     * "Any" rather than "every" targeted college, deliberately: a PCCOE coordinator
     * should see the PCCOE slice of a trust-wide drive. Narrowing the rows to their
     * own college is the query's job, not this check's.
     *
     * HoD and faculty coordinators are included -- they receive attendee lists for
     * their own department and need to see who applied.
     */
    public boolean canViewPool(UserPrincipal actor, Collection<Long> targetCollegeIds) {
        if (isTrustWide(actor)) {
            return true;
        }
        boolean allowed = targetCollegeIds.stream().anyMatch(collegeId ->
                actor.hasRoleForCollege(TNP_COLLEGE_ADMIN, collegeId)
                        || actor.hasRoleForCollege(TNP_COORDINATOR, collegeId)
                        || actor.hasRoleForCollege(HOD, collegeId)
                        || actor.hasRoleForCollege(FACULTY_DEPT_COORDINATOR, collegeId));
        if (!allowed) {
            log.warn("Pool read denied for actorId={} roles={}: no role in target colleges {}",
                    actor.userId(), actor.roles(), targetCollegeIds);
        }
        return allowed;
    }

    /**
     * Disqualifying an applicant removes a student from a drive they applied to, so
     * it stops at coordinator level: an HoD may read the pool but not change who is
     * in it.
     */
    public boolean canDisqualify(UserPrincipal actor, Collection<Long> targetCollegeIds) {
        if (isTrustWide(actor)) {
            return true;
        }
        boolean allowed = targetCollegeIds.stream().anyMatch(collegeId ->
                actor.hasRoleForCollege(TNP_COLLEGE_ADMIN, collegeId)
                        || actor.hasRoleForCollege(TNP_COORDINATOR, collegeId));
        if (!allowed) {
            log.warn("Disqualify denied for actorId={} roles={} colleges={}",
                    actor.userId(), actor.roles(), targetCollegeIds);
        }
        return allowed;
    }

    /**
     * Adding an applicant by hand bypasses the eligibility gate, and re-running
     * eligibility rewrites who can see a live drive. Both are college-admin level
     * and above.
     */
    public boolean canManagePool(UserPrincipal actor, Collection<Long> targetCollegeIds) {
        if (isTrustWide(actor)) {
            return true;
        }
        boolean allowed = targetCollegeIds.stream().anyMatch(collegeId ->
                actor.hasRoleForCollege(TNP_COLLEGE_ADMIN, collegeId));
        if (!allowed) {
            log.warn("Pool management denied for actorId={} roles={} colleges={}",
                    actor.userId(), actor.roles(), targetCollegeIds);
        }
        return allowed;
    }

    /** Recording an offer is a per-college act -- it changes that college's placement numbers. */
    public boolean canRecordOffer(UserPrincipal actor, Long collegeId) {
        boolean allowed = isTrustWide(actor)
                || actor.hasRoleForCollege(TNP_COLLEGE_ADMIN, collegeId)
                || actor.hasRoleForCollege(TNP_COORDINATOR, collegeId);
        if (!allowed) {
            log.warn("Offer recording denied for actorId={} roles={} targetCollegeId={}",
                    actor.userId(), actor.roles(), collegeId);
        }
        return allowed;
    }
}

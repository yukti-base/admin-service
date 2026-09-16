package org.yuktisetu.adminservice.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yuktisetu.core.security.UserPrincipal;
/**
 * Authorization rules for the Trust -> College -> Department hierarchy.
 *
 * Two rules are direct SSOT citations, one is not -- read the comment on
 * each method:
 *
 *  - College create/update/deactivate/restore/hard-delete: SSOT Section 9.1,
 *    verbatim: "College records: deletable/modifiable by IT Admin or Super
 *    Admin only."
 *  - Restore of soft-deleted records: SSOT Section 8, the admin portal for
 *    soft-deleted colleges is explicitly IT Admin / Super Admin.
 *  - Department authority is NOT specified anywhere in the SSOT. What's
 *    below (IT_ADMIN, TNP_SUPER_ADMIN, or a TNP_COLLEGE_ADMIN scoped to that
 *    specific college) is a judgment call, not a citation. Confirm it or
 *    override it -- don't assume I got this one from the spec.
 *  - Trust create is likewise not SSOT-specified; treated the same as
 *    College since it's the same tier of trust-wide structural change.
 */
@Slf4j
@Component
public class InstituteHierarchyPolicy {

    private static final String IT_ADMIN = "IT_ADMIN";
    private static final String TNP_SUPER_ADMIN = "TNP_SUPER_ADMIN";
    private static final String TNP_COLLEGE_ADMIN = "TNP_COLLEGE_ADMIN";

    public boolean canManageTrust(UserPrincipal actor) {
        boolean allowed = actor.hasRole(IT_ADMIN) || actor.hasRole(TNP_SUPER_ADMIN);
        if (!allowed) {
            log.warn("Trust-management action denied for actorId={} roles={}", actor.userId(), actor.roles());
        }
        return allowed;
    }

    public boolean canManageCollege(UserPrincipal actor) {
        // SSOT 9.1, verbatim -- see class javadoc.
        boolean allowed = actor.hasRole(IT_ADMIN) || actor.hasRole(TNP_SUPER_ADMIN);
        if (!allowed) {
            log.warn("College-management action denied for actorId={} roles={}", actor.userId(), actor.roles());
        }
        return allowed;
    }

    // Department authority is a judgment call (see class javadoc) -- a
    // TNP_COLLEGE_ADMIN can manage departments ONLY within a college they
    // are actually scoped to, never any college by virtue of the role alone.
    public boolean canManageDepartment(UserPrincipal actor, Long collegeId) {
        boolean allowed = actor.hasRole(IT_ADMIN)
                || actor.hasRole(TNP_SUPER_ADMIN)
                || actor.hasRoleForCollege(TNP_COLLEGE_ADMIN, collegeId);
        if (!allowed) {
            log.warn("Department-management action denied for actorId={} roles={} targetCollegeId={}",
                    actor.userId(), actor.roles(), collegeId);
        }
        return allowed;
    }

    // Restore (undelete) and hard-delete are both intentionally tighter than
    // plain "manage" for every tier -- IT Admin / Super Admin only, no
    // College Admin exception even for Department. Matches the same pattern
    // already established for role-assignment restore in auth-service:
    // reversing a deletion or making one permanent is trust-level authority,
    // not day-to-day college administration.
    public boolean canRestore(UserPrincipal actor) {
        boolean allowed = actor.hasRole(IT_ADMIN) || actor.hasRole(TNP_SUPER_ADMIN);
        if (!allowed) {
            log.warn("Restore action denied for actorId={} roles={}", actor.userId(), actor.roles());
        }
        return allowed;
    }

    public boolean canHardDelete(UserPrincipal actor) {
        boolean allowed = actor.hasRole(IT_ADMIN);
        if (!allowed) {
            log.warn("Hard-delete action denied for actorId={} roles={}", actor.userId(), actor.roles());
        }
        return allowed;
    }
}

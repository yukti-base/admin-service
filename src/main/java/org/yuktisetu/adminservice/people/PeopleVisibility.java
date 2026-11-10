package org.yuktisetu.adminservice.people;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.identity.model.RoleHierarchy;
import org.yuktisetu.identity.model.RoleType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Who the caller may find, as a list of grants -- one per role claim on their
 * token. A grant is "these target roles, within this college (and department)".
 *
 * The rule is RoleHierarchy.canView ("everything below", decided 2026-11-07),
 * the same table user-service's UserProfilePolicy uses, so a person the search
 * returns is always a person the caller can open.
 *
 * A person holding several roles sees the union of their grants: a coordinator
 * for PCCOE and for PCCOE-Akurdi gets two grants and sees both colleges. Reading
 * is safe to union -- unlike writes, there is no acting college to choose.
 */
@Slf4j
@Component
public class PeopleVisibility {

    /**
     * @param roles     target roles this grant reaches
     * @param collegeId null only for trust-wide viewers (IT / Super Admin)
     * @param deptId    non-null for dept-scoped viewers (HOD, FDC)
     */
    public record Grant(Set<RoleType> roles, Long collegeId, Long deptId) {
        public boolean trustWide() {
            return collegeId == null;
        }
    }

    public List<Grant> grantsFor(UserPrincipal actor) {
        List<Grant> grants = new ArrayList<>();
        for (UserPrincipal.RoleClaim claim : actor.roles()) {
            RoleType role;
            try {
                role = RoleType.valueOf(claim.role());
            } catch (IllegalArgumentException e) {
                continue; // a role name this build does not know grants nothing
            }
            EnumSet<RoleType> visible = RoleHierarchy.viewableBy(role);
            if (visible.isEmpty()) {
                continue;
            }
            if (RoleHierarchy.isTrustWide(role)) {
                grants.add(new Grant(visible, null, null));
            } else if (claim.collegeId() == null) {
                // A college-scoped role without a college on the token is malformed;
                // treating it as trust-wide would be a silent escalation.
                log.warn("People search: ignoring claim role={} with no collegeId for actorId={}", role, actor.userId());
            } else if (RoleHierarchy.isDeptScoped(role) && claim.deptId() == null) {
                log.warn("People search: ignoring claim role={} with no deptId for actorId={}", role, actor.userId());
            } else {
                grants.add(new Grant(visible, claim.collegeId(),
                        RoleHierarchy.isDeptScoped(role) ? claim.deptId() : null));
            }
        }
        return grants;
    }

    /** Soft-deleted people are the IT Admin's alone (decided 2026-11-07). */
    public boolean canIncludeDeleted(UserPrincipal actor) {
        return actor.hasRole(RoleType.IT_ADMIN.name());
    }

    public static EnumSet<RoleType> visibleRoles(List<Grant> grants) {
        EnumSet<RoleType> all = EnumSet.noneOf(RoleType.class);
        grants.forEach(g -> all.addAll(g.roles()));
        return all;
    }
}

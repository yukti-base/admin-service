package org.yuktisetu.adminservice.security;

import java.util.List;

// Kept field-for-field identical to auth-service's UserPrincipal on purpose --
// that class's own doc comment says every downstream service should
// reconstruct this shape verbatim from the JWT claims rather than calling
// back to auth-service per request. Don't let this drift from the original.
public record UserPrincipal(
        Long userId,
        String email,
        List<RoleClaim> roles
) {
    public record RoleClaim(String role, Long collegeId, Long deptId) {}

    public boolean hasRole(String role) {
        return roles.stream().anyMatch(r -> r.role().equals(role));
    }

    // Trust-wide roles (IT_ADMIN, TNP_SUPER_ADMIN) carry null collegeId --
    // any match on role name is scope-sufficient for them. College-scoped
    // roles (TNP_COLLEGE_ADMIN today) must match the specific collegeId too,
    // since one person can hold TNP_COLLEGE_ADMIN across several -- but not
    // all -- colleges (see cpms-roles-rbac: multi-college scoping).
    public boolean hasRoleForCollege(String role, Long collegeId) {
        return roles.stream().anyMatch(r ->
                r.role().equals(role) &&
                        (r.collegeId() == null || r.collegeId().equals(collegeId)));
    }
}

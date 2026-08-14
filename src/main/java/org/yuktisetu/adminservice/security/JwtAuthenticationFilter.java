package org.yuktisetu.adminservice.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenVerifier tokenVerifier;

    public JwtAuthenticationFilter(JwtTokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = tokenVerifier.verify(token);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> roleClaims = claims.get("roles", List.class);

                List<UserPrincipal.RoleClaim> roles = roleClaims.stream()
                        .map(m -> new UserPrincipal.RoleClaim(
                                (String) m.get("role"),
                                // FIX vs. auth-service's copy of this filter: after a JWT's JSON
                                // round-trip, collegeId/deptId come back as a Number (Integer or
                                // Long depending on magnitude), never a String -- JwtTokenProvider
                                // puts a real Long/null into the claim, it does not stringify it.
                                // auth-service's filter casts this straight to (String), which
                                // throws ClassCastException for every non-null value, gets
                                // swallowed by the catch-all below, and silently deauthenticates
                                // anyone whose role isn't trust-wide. Read it as a Number instead.
                                toLongOrNull(m.get("collegeId")),
                                toLongOrNull(m.get("deptId"))
                        ))
                        .toList();

                UserPrincipal principal = new UserPrincipal(
                        Long.parseLong(claims.getSubject()),
                        claims.get("email", String.class),
                        roles
                );

                List<GrantedAuthority> authorities = roles.stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r.role()))
                        .map(GrantedAuthority.class::cast)
                        .toList();

                var authToken = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } catch (Exception e) {
                // Invalid/expired token: leave SecurityContext empty. Downstream
                // authorization rejects the request as unauthenticated (401)
                // instead of a raw 500 from an unhandled parse exception.
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }

    private Long toLongOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        // Defensive fallback only -- should never hit this branch given how
        // JwtTokenProvider serializes the claim, but a string-typed claim
        // shouldn't crash the filter either.
        String s = v.toString();
        return s.isBlank() ? null : Long.parseLong(s);
    }
}

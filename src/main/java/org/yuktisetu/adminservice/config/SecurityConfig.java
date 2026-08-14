package org.yuktisetu.adminservice.config;

import org.yuktisetu.adminservice.security.JwtAuthenticationFilter;
import org.yuktisetu.adminservice.security.JwtTokenVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
// auth-service is MISSING this annotation, which means every @PreAuthorize
// on RoleManagementController is currently silently ignored there -- go add
// it there too. Without @EnableMethodSecurity, Spring Security never even
// looks at @PreAuthorize; the only thing actually gating those endpoints
// today is the service-layer RoleHierarchyPolicy check, which happens to
// save you, but the annotations are lying about what's enforcing what.
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtTokenVerifier tokenVerifier) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless bearer-token API, no cookies/sessions to protect
            .cors(cors -> {})
            .sessionManagement(sm ->
                    sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(
                    new JwtAuthenticationFilter(tokenVerifier),
                    UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }
}

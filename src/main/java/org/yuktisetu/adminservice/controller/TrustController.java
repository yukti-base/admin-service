package org.yuktisetu.adminservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.yuktisetu.adminservice.dto.StatusChangeRequest;
import org.yuktisetu.adminservice.dto.TrustRequest;
import org.yuktisetu.adminservice.dto.TrustResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.adminservice.service.TrustService;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/trusts")
@RequiredArgsConstructor
public class TrustController {

    private final TrustService trustService;

    // Coarse gate here, same philosophy as auth-service's controllers: this
    // just keeps an obviously-wrong caller (a Student/HoD token) from
    // reaching the service. The real decision is InstituteHierarchyPolicy,
    // inside TrustService -- this annotation is defense-in-depth, not the
    // authority. It only works at all because SecurityConfig has
    // @EnableMethodSecurity -- auth-service's equivalent annotations
    // currently don't, see the note in that SecurityConfig.
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<TrustResponse> create(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody @Valid TrustRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trustService.create(actor, request));
    }

    @GetMapping
    public ResponseEntity<List<TrustResponse>> listActive() {
        return ResponseEntity.ok(trustService.listActive());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TrustResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(trustService.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<TrustResponse> update(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid TrustRequest request) {
        return ResponseEntity.ok(trustService.update(actor, id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<TrustResponse> setStatus(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid StatusChangeRequest request) {
        return ResponseEntity.ok(trustService.setStatus(actor, id, request.status()));
    }

    @PostMapping("/{id}/soft-delete")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<Void> softDelete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        trustService.softDelete(actor, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<TrustResponse> restore(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        return ResponseEntity.ok(trustService.restore(actor, id));
    }

    // Irreversible + IT-Admin-only gets its own path rather than DELETE
    // /trusts/{id} -- same reasoning as auth-service's /roles/hard-delete:
    // an irreversible action shouldn't be one generic REST client default
    // away from being triggered by accident.
    @PostMapping("/{id}/hard-delete")
    @PreAuthorize("hasAuthority('ROLE_IT_ADMIN')")
    public ResponseEntity<Void> hardDelete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        trustService.hardDelete(actor, id);
        return ResponseEntity.noContent().build();
    }
}

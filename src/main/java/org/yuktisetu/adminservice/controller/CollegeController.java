package org.yuktisetu.adminservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.yuktisetu.adminservice.dto.CollegeRequest;
import org.yuktisetu.adminservice.dto.CollegeResponse;
import org.yuktisetu.adminservice.dto.StatusChangeRequest;
import org.yuktisetu.adminservice.security.UserPrincipal;
import org.yuktisetu.adminservice.service.CollegeService;

import java.util.List;

@RestController
@RequestMapping("/colleges")
@RequiredArgsConstructor
public class CollegeController {

    private final CollegeService collegeService;

    // College authority is IT_ADMIN/TNP_SUPER_ADMIN only per SSOT 9.1 --
    // no TNP_COLLEGE_ADMIN here, unlike DepartmentController below. A
    // College Admin manages what's INSIDE their college, not the college
    // record itself.
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<CollegeResponse> create(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody @Valid CollegeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(collegeService.create(actor, request));
    }

    @GetMapping
    public ResponseEntity<List<CollegeResponse>> listForTrust(@RequestParam Long trustId) {
        return ResponseEntity.ok(collegeService.listActiveForTrust(trustId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CollegeResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(collegeService.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<CollegeResponse> update(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid CollegeRequest request) {
        return ResponseEntity.ok(collegeService.update(actor, id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<CollegeResponse> setStatus(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid StatusChangeRequest request) {
        return ResponseEntity.ok(collegeService.setStatus(actor, id, request.status()));
    }

    @PostMapping("/{id}/soft-delete")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<Void> softDelete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        collegeService.softDelete(actor, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<CollegeResponse> restore(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        return ResponseEntity.ok(collegeService.restore(actor, id));
    }

    @PostMapping("/{id}/hard-delete")
    @PreAuthorize("hasAuthority('ROLE_IT_ADMIN')")
    public ResponseEntity<Void> hardDelete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        collegeService.hardDelete(actor, id);
        return ResponseEntity.noContent().build();
    }
}

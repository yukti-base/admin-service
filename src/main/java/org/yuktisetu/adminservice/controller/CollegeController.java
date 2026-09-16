package org.yuktisetu.adminservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.yuktisetu.adminservice.dto.CollegeRequest;
import org.yuktisetu.adminservice.dto.CollegeResponse;
import org.yuktisetu.adminservice.dto.StatusChangeRequest;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.adminservice.service.CollegeService;

import java.util.List;

@Slf4j
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
    public ResponseEntity<YuktiSetuResponse<CollegeResponse>> create(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody @Valid CollegeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(YuktiSetuResponse.success(collegeService.create(actor, request), 201, "College created"));
    }

    @GetMapping
    public ResponseEntity<YuktiSetuResponse<List<CollegeResponse>>> listForTrust(@RequestParam Long trustId) {
        return ResponseEntity.ok(YuktiSetuResponse.success(collegeService.listActiveForTrust(trustId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<YuktiSetuResponse<CollegeResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(collegeService.get(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<CollegeResponse>> update(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid CollegeRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(collegeService.update(actor, id, request), "College updated"));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<CollegeResponse>> setStatus(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid StatusChangeRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(collegeService.setStatus(actor, id, request.status()), "College status updated"));
    }

    @PostMapping("/{id}/soft-delete")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<Void>> softDelete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        collegeService.softDelete(actor, id);
        return ResponseEntity.ok(YuktiSetuResponse.success(null, "College soft-deleted"));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<CollegeResponse>> restore(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(collegeService.restore(actor, id), "College restored"));
    }

    @PostMapping("/{id}/hard-delete")
    @PreAuthorize("hasAuthority('ROLE_IT_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<Void>> hardDelete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        collegeService.hardDelete(actor, id);
        return ResponseEntity.ok(YuktiSetuResponse.success(null, "College hard-deleted"));
    }
}

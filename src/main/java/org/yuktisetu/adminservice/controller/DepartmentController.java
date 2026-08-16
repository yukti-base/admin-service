package org.yuktisetu.adminservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.yuktisetu.adminservice.dto.DepartmentRequest;
import org.yuktisetu.adminservice.dto.DepartmentResponse;
import org.yuktisetu.adminservice.dto.StatusChangeRequest;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.adminservice.service.DepartmentService;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    // TNP_COLLEGE_ADMIN included at the coarse gate, unlike CollegeController --
    // the fine-grained "which college are they actually scoped to" check
    // happens inside DepartmentService via InstituteHierarchyPolicy.canManageDepartment,
    // this annotation only screens out roles that can NEVER touch a department
    // (Student, Ground Volunteer, etc.).
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<DepartmentResponse> create(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody @Valid DepartmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(departmentService.create(actor, request));
    }

    @GetMapping
    public ResponseEntity<List<DepartmentResponse>> listForCollege(@RequestParam Long collegeId) {
        return ResponseEntity.ok(departmentService.listActiveForCollege(collegeId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DepartmentResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(departmentService.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<DepartmentResponse> update(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid DepartmentRequest request) {
        return ResponseEntity.ok(departmentService.update(actor, id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<DepartmentResponse> setStatus(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid StatusChangeRequest request) {
        return ResponseEntity.ok(departmentService.setStatus(actor, id, request.status()));
    }

    // Restore/soft-delete/hard-delete are IT_ADMIN/TNP_SUPER_ADMIN only even
    // for Department -- the coarse gate is tighter here than create/update
    // above, deliberately: DepartmentService enforces the same narrowing
    // internally, this just reflects it at the controller too.
    @PostMapping("/{id}/soft-delete")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<Void> softDelete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        departmentService.softDelete(actor, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<DepartmentResponse> restore(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        return ResponseEntity.ok(departmentService.restore(actor, id));
    }

    @PostMapping("/{id}/hard-delete")
    @PreAuthorize("hasAuthority('ROLE_IT_ADMIN')")
    public ResponseEntity<Void> hardDelete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        departmentService.hardDelete(actor, id);
        return ResponseEntity.noContent().build();
    }
}

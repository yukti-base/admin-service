package org.yuktisetu.adminservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.adminservice.dto.CompanyContactRequest;
import org.yuktisetu.adminservice.dto.CompanyContactResponse;
import org.yuktisetu.adminservice.dto.CompanyRequest;
import org.yuktisetu.adminservice.dto.CompanyResponse;
import org.yuktisetu.adminservice.dto.CompanyStatusChangeRequest;
import org.yuktisetu.adminservice.service.CompanyService;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.model.CompanyStatus;

import java.util.List;

/**
 * Company onboarding -- the first half of "a company comes to the TnP cell".
 * Nothing can be posted until a company exists here.
 *
 * The @PreAuthorize gates are coarse, exactly as on CollegeController: they keep
 * an obviously-wrong caller (a Student or Ground Volunteer token) from reaching
 * the service at all. The real decision is PlacementDrivePolicy.
 */
@Slf4j
@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')")
    public ResponseEntity<YuktiSetuResponse<CompanyResponse>> create(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody @Valid CompanyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(YuktiSetuResponse.success(companyService.create(actor, request), 201, "Company onboarded"));
    }

    @GetMapping
    public ResponseEntity<YuktiSetuResponse<Page<CompanyResponse>>> list(
            @RequestParam(required = false) CompanyStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<CompanyResponse> result = companyService.list(
                status, PageRequest.of(page, size, Sort.by("name").ascending()));
        return ResponseEntity.ok(YuktiSetuResponse.success(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<YuktiSetuResponse<CompanyResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(companyService.get(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')")
    public ResponseEntity<YuktiSetuResponse<CompanyResponse>> update(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid CompanyRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                companyService.update(actor, id, request), "Company updated"));
    }

    /** Also the blacklist path -- the service gates that harder and demands a reason. */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<CompanyResponse>> setStatus(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid CompanyStatusChangeRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                companyService.setStatus(actor, id, request), "Company status updated"));
    }

    @PostMapping("/{id}/soft-delete")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<Void>> softDelete(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        companyService.softDelete(actor, id);
        return ResponseEntity.ok(YuktiSetuResponse.success(null, "Company soft-deleted"));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<CompanyResponse>> restore(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                companyService.restore(actor, id), "Company restored"));
    }

    @PostMapping("/{id}/hard-delete")
    @PreAuthorize("hasAuthority('ROLE_IT_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<Void>> hardDelete(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        companyService.hardDelete(actor, id);
        return ResponseEntity.ok(YuktiSetuResponse.success(null, "Company permanently deleted"));
    }

    // --- Contacts ---

    @PostMapping("/{id}/contacts")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')")
    public ResponseEntity<YuktiSetuResponse<CompanyContactResponse>> addContact(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid CompanyContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(YuktiSetuResponse.success(companyService.addContact(actor, id, request), 201, "Contact added"));
    }

    @GetMapping("/{id}/contacts")
    public ResponseEntity<YuktiSetuResponse<List<CompanyContactResponse>>> listContacts(@PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(companyService.listContacts(id)));
    }

    @DeleteMapping("/{id}/contacts/{contactId}")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')")
    public ResponseEntity<YuktiSetuResponse<Void>> removeContact(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @PathVariable Long contactId) {
        companyService.removeContact(actor, id, contactId);
        return ResponseEntity.ok(YuktiSetuResponse.success(null, "Contact removed"));
    }
}

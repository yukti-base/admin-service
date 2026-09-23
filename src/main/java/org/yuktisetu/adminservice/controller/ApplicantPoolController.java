package org.yuktisetu.adminservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.adminservice.dto.ApplicantResponse;
import org.yuktisetu.adminservice.dto.DisqualifyRequest;
import org.yuktisetu.adminservice.dto.EligibilityRunResponse;
import org.yuktisetu.adminservice.dto.ManualApplicantRequest;
import org.yuktisetu.adminservice.service.ApplicantPoolService;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.model.ApplicationStatus;

import java.util.List;
import java.util.Map;

/**
 * The TnP's applicant pool.
 *
 * Deliberately in admin-service rather than drive-service: every administrative
 * operation lives here so that an admin never waits on, or fails because of, a
 * service that is saturated serving students mid-drive.
 *
 * The @PreAuthorize gates are coarse; PlacementDrivePolicy does the college
 * scoping that actually keeps one college's pipeline out of another's hands.
 */
@Slf4j
@RestController
@RequestMapping("/postings")
@RequiredArgsConstructor
public class ApplicantPoolController {

    private static final String POOL_READERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN',"
                    + "'ROLE_TNP_COORDINATOR','ROLE_HOD','ROLE_FACULTY_DEPT_COORDINATOR')";

    private static final String POOL_WRITERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')";

    private static final String POOL_MANAGERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')";

    private final ApplicantPoolService poolService;

    @GetMapping("/{id}/applications")
    @PreAuthorize(POOL_READERS)
    public ResponseEntity<YuktiSetuResponse<Page<ApplicantResponse>>> listApplicants(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // Sort is fixed in the query (applied_at, id) rather than taken from the
        // caller: an unindexed sort column on a 100k-row table is a silent table
        // scan, and this ordering is the one the composite index serves.
        Page<ApplicantResponse> result = poolService.listApplicants(
                actor, id, status, PageRequest.of(page, Math.min(size, 200)));
        return ResponseEntity.ok(YuktiSetuResponse.success(result));
    }

    /** Counts per status for the pool header, one grouped query. */
    @GetMapping("/{id}/applications/counts")
    @PreAuthorize(POOL_READERS)
    public ResponseEntity<YuktiSetuResponse<Map<String, Long>>> counts(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(poolService.statusCounts(actor, id)));
    }

    @GetMapping("/{id}/eligibility/runs")
    @PreAuthorize(POOL_READERS)
    public ResponseEntity<YuktiSetuResponse<List<EligibilityRunResponse>>> listRuns(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(poolService.listRuns(actor, id)));
    }

    /**
     * Queue a re-resolution. Returns 202 with a RUNNING run to poll -- the caller
     * is never held open for a cohort-sized batch job.
     */
    @PostMapping("/{id}/eligibility/re-evaluate")
    @PreAuthorize(POOL_MANAGERS)
    public ResponseEntity<YuktiSetuResponse<EligibilityRunResponse>> reEvaluate(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        EligibilityRunResponse run = poolService.reEvaluate(actor, id);
        return ResponseEntity.accepted()
                .body(YuktiSetuResponse.success(run, HttpStatus.ACCEPTED.value(),
                        "Eligibility resolution started"));
    }

    /** Add applicants by hand -- single or bulk. Bypasses the gate; audited as TNP_ADDED. */
    @PostMapping("/{id}/applications")
    @PreAuthorize(POOL_MANAGERS)
    public ResponseEntity<YuktiSetuResponse<List<ApplicantResponse>>> addManually(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid ManualApplicantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(YuktiSetuResponse.success(
                        poolService.addManually(actor, id, request), 201, "Applicants added"));
    }

    @PostMapping("/{id}/applications/{applicationId}/disqualify")
    @PreAuthorize(POOL_WRITERS)
    public ResponseEntity<YuktiSetuResponse<ApplicantResponse>> disqualify(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @PathVariable Long applicationId,
            @RequestBody @Valid DisqualifyRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                poolService.disqualify(actor, id, applicationId, request.reason()),
                "Applicant disqualified"));
    }
}

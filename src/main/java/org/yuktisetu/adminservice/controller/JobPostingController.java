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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.adminservice.dto.EligibilityCriteriaRequest;
import org.yuktisetu.adminservice.dto.EligibilityCriteriaResponse;
import org.yuktisetu.adminservice.dto.JobPostingRequest;
import org.yuktisetu.adminservice.dto.JobPostingResponse;
import org.yuktisetu.adminservice.dto.PostingCloseRequest;
import org.yuktisetu.adminservice.dto.ParseJdRequest;
import org.yuktisetu.adminservice.dto.ParsedJdResponse;
import org.yuktisetu.adminservice.dto.PostingDocumentRequest;
import org.yuktisetu.adminservice.dto.PostingDocumentResponse;
import org.yuktisetu.adminservice.dto.PostingTargetsRequest;
import org.yuktisetu.adminservice.dto.ReapplicationPolicyRequest;
import org.yuktisetu.adminservice.dto.ReapplicationPolicyResponse;
import org.yuktisetu.adminservice.service.ApplicantPoolService;
import org.yuktisetu.adminservice.jd.JdParser;
import org.yuktisetu.adminservice.service.JobPostingService;
import org.yuktisetu.adminservice.service.PostingEligibilityService;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.model.PostingStatus;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.yuktisetu.drive.model.PostingDocumentType;

/**
 * Drive authoring. A posting is assembled in DRAFT across several calls -- JD,
 * targets, criteria, re-application policy -- and publish is the one step that
 * checks the whole thing holds together before any student hears about it.
 *
 * Authoring is open from TNP_SUPER_ADMIN down to TNP_COORDINATOR. Publishing
 * narrows further inside PlacementDrivePolicy: a college-scoped actor may only
 * publish a drive whose targets stay within their own colleges.
 */
@Slf4j
@RestController
@RequestMapping("/postings")
@RequiredArgsConstructor
public class JobPostingController {

    private static final String DRAFTERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')";

    private final JobPostingService postingService;
    private final PostingEligibilityService eligibilityService;
    private final ApplicantPoolService poolService;
    private final JdParser jdParser;

    @PostMapping
    @PreAuthorize(DRAFTERS)
    public ResponseEntity<YuktiSetuResponse<JobPostingResponse>> create(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody @Valid JobPostingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(YuktiSetuResponse.success(postingService.createDraft(actor, request), 201, "Draft posting created"));
    }

    @GetMapping
    public ResponseEntity<YuktiSetuResponse<Page<JobPostingResponse>>> list(
            @RequestParam(required = false) PostingStatus status,
            @RequestParam(required = false) Long companyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<JobPostingResponse> result = postingService.list(
                status, companyId, PageRequest.of(page, size, Sort.by("applicationDeadline").descending()));
        return ResponseEntity.ok(YuktiSetuResponse.success(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<YuktiSetuResponse<JobPostingResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(postingService.get(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize(DRAFTERS)
    public ResponseEntity<YuktiSetuResponse<JobPostingResponse>> update(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid JobPostingRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                postingService.update(actor, id, request), "Posting updated"));
    }

    /** Replaces the whole target set -- who this drive is open to. */
    @PutMapping("/{id}/targets")
    @PreAuthorize(DRAFTERS)
    public ResponseEntity<YuktiSetuResponse<JobPostingResponse>> setTargets(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid PostingTargetsRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                postingService.setTargets(actor, id, request), "Targets updated"));
    }

    @PutMapping("/{id}/criteria")
    @PreAuthorize(DRAFTERS)
    public ResponseEntity<YuktiSetuResponse<EligibilityCriteriaResponse>> setCriteria(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid EligibilityCriteriaRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                eligibilityService.setCriteria(actor, id, request), "Eligibility criteria updated"));
    }

    @GetMapping("/{id}/criteria")
    public ResponseEntity<YuktiSetuResponse<EligibilityCriteriaResponse>> getCriteria(@PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(eligibilityService.getCriteria(id)));
    }

    @PutMapping("/{id}/reapplication-policy")
    @PreAuthorize(DRAFTERS)
    public ResponseEntity<YuktiSetuResponse<ReapplicationPolicyResponse>> setReapplicationPolicy(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid ReapplicationPolicyRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                eligibilityService.setReapplicationPolicy(actor, id, request), "Re-application policy updated"));
    }

    @GetMapping("/{id}/reapplication-policy")
    public ResponseEntity<YuktiSetuResponse<ReapplicationPolicyResponse>> getReapplicationPolicy(@PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(eligibilityService.getReapplicationPolicy(id)));
    }

    /**
     * Register a JD (or other document) already uploaded to object storage.
     * Required before publish -- the bytes never pass through this service.
     */
        /**
     * Upload a job-description file and attach it in one synchronous call.
     *
     * The TnP is waiting in the drive builder, so the response carries the stored
     * key and the document row -- no second request, no polling. The JSON variant
     * below stays for the case where a key already exists.
     */
    /**
     * Reads pasted JD text into drive fields. Pure function: nothing is written,
     * nothing is persisted, no posting id is involved. The caller reviews the
     * proposals field by field and then creates or updates a draft as normal.
     *
     * Separate from the posting lifecycle on purpose -- it is used BEFORE a
     * posting exists, and keeping it side-effect-free is what makes it safe for
     * a coordinator to paste, look, fix the text and paste again.
     */
    @PostMapping("/parse-jd")
    @PreAuthorize(DRAFTERS)
    public ResponseEntity<YuktiSetuResponse<ParsedJdResponse>> parseJd(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody @Valid ParseJdRequest request) {

        log.info("POST /postings/parse-jd chars={} actorId={}", request.text().length(), actor.userId());
        return ResponseEntity.ok(YuktiSetuResponse.success(
                jdParser.parse(request.text()), "Job description parsed"));
    }

    @PostMapping(value = "/{id}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(DRAFTERS)
    public ResponseEntity<YuktiSetuResponse<PostingDocumentResponse>> uploadDocument(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file,
            @RequestParam("docType") PostingDocumentType docType,
            @RequestParam(value = "primary", required = false) Boolean primary) {

        log.info("POST /postings/{}/documents/upload type={} size={} actorId={}",
                id, docType, file.getSize(), actor.userId());

        return ResponseEntity.status(201).body(YuktiSetuResponse.success(
                postingService.uploadDocument(actor, id, docType, file, primary), 201, "Document uploaded"));
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize(DRAFTERS)
    public ResponseEntity<YuktiSetuResponse<PostingDocumentResponse>> addDocument(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid PostingDocumentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(YuktiSetuResponse.success(
                        postingService.addDocument(actor, id, request), 201, "Document attached"));
    }

    @GetMapping("/{id}/documents")
    public ResponseEntity<YuktiSetuResponse<List<PostingDocumentResponse>>> listDocuments(@PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(postingService.listDocuments(id)));
    }

    /**
     * The irreversible step. Opens applications and queues the notification to
     * every eligible student, both inside one transaction.
     */
    @PostMapping("/{id}/publish")
    @PreAuthorize(DRAFTERS)
    public ResponseEntity<YuktiSetuResponse<JobPostingResponse>> publish(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        JobPostingResponse published = postingService.publish(actor, id);

        // Resolution is kicked off AFTER publish returns, so it runs against a
        // committed PUBLISHED row -- firing it from inside the publish transaction
        // would let the background worker read a posting that is still a draft.
        // It is queued, not awaited: the coordinator's screen should come back the
        // moment the drive is live, not after a whole cohort has been evaluated.
        postingService.startEligibilityResolution(actor, id);

        return ResponseEntity.ok(YuktiSetuResponse.success(published, "Posting published"));
    }

    /** Close applications early. The scheduled sweep does the same at the deadline. */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<JobPostingResponse>> close(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody(required = false) @Valid PostingCloseRequest request) {
        String reason = request == null ? null : request.reason();
        return ResponseEntity.ok(YuktiSetuResponse.success(
                postingService.close(actor, id, reason), "Applications closed"));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<JobPostingResponse>> cancel(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid PostingCloseRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                postingService.cancel(actor, id, request.reason()), "Posting cancelled"));
    }
}

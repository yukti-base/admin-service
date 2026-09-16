package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.dto.JobPostingRequest;
import org.yuktisetu.adminservice.dto.PostingDocumentRequest;
import org.yuktisetu.adminservice.dto.PostingDocumentResponse;
import org.yuktisetu.adminservice.dto.JobPostingResponse;
import org.yuktisetu.adminservice.dto.PostingTargetsRequest;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.policy.PlacementDrivePolicy;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.db.College;
import org.yuktisetu.db.Company;
import org.yuktisetu.db.Department;
import org.yuktisetu.db.JobPosting;
import org.yuktisetu.db.JobPostingDocument;
import org.yuktisetu.db.PostingLocation;
import org.yuktisetu.db.PostingSkill;
import org.yuktisetu.db.PostingTarget;
import org.yuktisetu.events.DriveTopics;
import org.yuktisetu.events.PostingPublishedEvent;
import org.yuktisetu.model.CompanyStatus;
import org.yuktisetu.model.EligibilityRunReason;
import org.yuktisetu.model.PackageBasis;
import org.yuktisetu.model.PostingDocumentType;
import org.yuktisetu.model.PostingStatus;
import org.yuktisetu.model.PostingStatusTransitions;
import org.yuktisetu.model.ShortlistingMode;
import org.yuktisetu.repository.CollegeRepository;
import org.yuktisetu.repository.CompanyRepository;
import org.yuktisetu.repository.DepartmentRepository;
import org.yuktisetu.repository.JobPostingDocumentRepository;
import org.yuktisetu.repository.JobPostingRepository;
import org.yuktisetu.repository.PostingEligibilityCriteriaRepository;
import org.yuktisetu.repository.PostingLocationRepository;
import org.yuktisetu.repository.PostingReapplicationPolicyRepository;
import org.yuktisetu.repository.PostingSkillRepository;
import org.yuktisetu.repository.PostingTargetRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Authoring and publishing a drive.
 *
 * A posting is built up in DRAFT across several calls -- the JD, then targets,
 * then criteria, then the re-application policy -- and publish is the single
 * irreversible step that checks the whole thing is coherent before any student
 * hears about it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobPostingService {

    private final JobPostingRepository postingRepository;
    private final CompanyRepository companyRepository;
    private final CollegeRepository collegeRepository;
    private final DepartmentRepository departmentRepository;
    private final PostingTargetRepository targetRepository;
    private final PostingSkillRepository skillRepository;
    private final PostingLocationRepository locationRepository;
    private final PostingEligibilityCriteriaRepository criteriaRepository;
    private final PostingReapplicationPolicyRepository reapplicationPolicyRepository;
    private final JobPostingDocumentRepository documentRepository;
    private final PlacementDrivePolicy policy;
    private final OutboxWriter outboxWriter;
    private final EligibilityResolutionService resolutionService;
    private final EligibilityRunLauncher runLauncher;
    private final ApplicantPoolService poolService;

    @Transactional
    public JobPostingResponse createDraft(UserPrincipal actor, JobPostingRequest req) {
        try {
            return doCreateDraft(actor, req);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create draft posting code={} actorId={}: {}",
                    req.postingCode(), actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not create the posting. Please try again.");
        }
    }

    private JobPostingResponse doCreateDraft(UserPrincipal actor, JobPostingRequest req) {
        if (!policy.canDraftPosting(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Company company = companyRepository.findByIdAndIsDeletedFalse(req.companyId())
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Company", req.companyId()));
        if (company.getStatus() == CompanyStatus.BLACKLISTED) {
            throw new AdminExceptions.CompanyNotActiveException(company.getName(), company.getStatus().name());
        }
        String code = req.postingCode().trim();
        if (postingRepository.existsByCompany_IdAndPostingCodeIgnoreCase(company.getId(), code)) {
            throw new AdminExceptions.DuplicateCodeException("JobPosting", code);
        }

        JobPosting posting = new JobPosting();
        posting.setCompany(company);
        posting.setPostingCode(code);
        posting.setStatus(PostingStatus.DRAFT);
        apply(posting, req);
        posting.setCreatedBy(actor.userId());
        posting.setUpdatedBy(actor.userId());

        JobPosting saved = postingRepository.save(posting);
        replaceSkills(saved, req, actor);
        replaceLocations(saved, req, actor);

        log.info("Created draft posting id={} code={} companyId={} actorId={}",
                saved.getId(), code, company.getId(), actor.userId());
        return toResponse(saved);
    }

    @Transactional
    public JobPostingResponse update(UserPrincipal actor, Long id, JobPostingRequest req) {
        try {
            return doUpdate(actor, id, req);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update posting id={} actorId={}: {}", id, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not update the posting. Please try again.");
        }
    }

    private JobPostingResponse doUpdate(UserPrincipal actor, Long id, JobPostingRequest req) {
        if (!policy.canDraftPosting(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        JobPosting posting = requireEditable(id);
        apply(posting, req);
        posting.setUpdatedBy(actor.userId());
        JobPosting saved = postingRepository.save(posting);
        replaceSkills(saved, req, actor);
        replaceLocations(saved, req, actor);
        log.info("Updated posting id={} actorId={}", id, actor.userId());
        return toResponse(saved);
    }

    /**
     * Replaces the entire target set. Deliberately not a patch: a half-edited
     * audience is how a drive ends up notifying a batch nobody meant to include.
     */
    @Transactional
    public JobPostingResponse setTargets(UserPrincipal actor, Long id, PostingTargetsRequest req) {
        try {
            return doSetTargets(actor, id, req);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to set targets on posting id={} actorId={}: {}", id, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not update the drive's targets. Please try again.");
        }
    }

    private JobPostingResponse doSetTargets(UserPrincipal actor, Long id, PostingTargetsRequest req) {
        if (!policy.canDraftPosting(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        JobPosting posting = requireEditable(id);

        targetRepository.deleteByPosting_Id(id);
        targetRepository.flush();

        Set<String> seen = new LinkedHashSet<>();
        for (PostingTargetsRequest.TargetRequest t : req.targets()) {
            College college = collegeRepository.findByIdAndIsDeletedFalse(t.collegeId())
                    .orElseThrow(() -> new AdminExceptions.NotFoundException("College", t.collegeId()));

            Department department = null;
            if (t.departmentId() != null) {
                department = departmentRepository.findByIdAndIsDeletedFalse(t.departmentId())
                        .orElseThrow(() -> new AdminExceptions.NotFoundException("Department", t.departmentId()));
                // A department belonging to a different college would silently
                // widen the audience to students the TnP never selected.
                if (!department.getCollege().getId().equals(college.getId())) {
                    throw new AdminExceptions.InvalidRequestException(
                            "Department " + t.departmentId() + " does not belong to college " + t.collegeId() + ".");
                }
            }

            String key = targetKey(t.collegeId(), t.departmentId(), t.batchYear(), t.degree());
            if (!seen.add(key)) {
                throw new AdminExceptions.DuplicateTargetException(key);
            }

            PostingTarget target = new PostingTarget();
            target.setPosting(posting);
            target.setCollege(college);
            target.setDepartment(department);
            target.setBatchYear(t.batchYear());
            target.setDegree(t.degree());
            target.setTargetKey(key);
            target.setCreatedBy(actor.userId());
            target.setUpdatedBy(actor.userId());
            targetRepository.save(target);
        }

        // The cached target-college set backs every applicant-pool authorization
        // check, so it has to go the moment the targets change.
        poolService.evictTargetColleges(id);

        log.info("Set {} target(s) on posting id={} actorId={}", req.targets().size(), id, actor.userId());
        return toResponse(posting);
    }

    /**
     * Publish. The irreversible step: it opens applications and triggers the
     * notification to every eligible student.
     *
     * Everything below runs in one transaction, including the outbox row. If any
     * precondition fails nothing is written, and if the transaction rolls back
     * the notification cannot have been sent -- which is exactly why the event is
     * queued here rather than pushed to Kafka after the commit.
     */
    @Transactional
    public JobPostingResponse publish(UserPrincipal actor, Long id) {
        try {
            return doPublish(actor, id);
        } catch (ApiException e) {
            // Publish refusals are all specific and actionable -- missing targets,
            // no JD, deadline in the past -- and the coordinator needs to read them
            // verbatim to know what to fix.
            throw e;
        } catch (Exception e) {
            log.error("Failed to publish posting id={} actorId={}: {}", id, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not publish the drive. Please try again.");
        }
    }

    private JobPostingResponse doPublish(UserPrincipal actor, Long id) {
        JobPosting posting = postingRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", id));

        if (!PostingStatusTransitions.canTransition(posting.getStatus(), PostingStatus.PUBLISHED)) {
            throw new AdminExceptions.IllegalStatusTransitionException(
                    "JobPosting", posting.getStatus().name(), PostingStatus.PUBLISHED.name());
        }

        List<PostingTarget> targets = targetRepository.findByPosting_IdAndIsDeletedFalse(id);
        if (targets.isEmpty()) {
            throw new AdminExceptions.PostingNotPublishableException(
                    "it has no targets, so no student would ever see it");
        }

        List<Long> targetCollegeIds = targets.stream().map(PostingTarget::getCollegeId).distinct().toList();
        if (!policy.canPublishPosting(actor, targetCollegeIds)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }

        Company company = posting.getCompany();
        if (company.isDeleted() || company.getStatus() == CompanyStatus.BLACKLISTED
                || company.getStatus() == CompanyStatus.INACTIVE) {
            throw new AdminExceptions.CompanyNotActiveException(company.getName(), company.getStatus().name());
        }

        if (!criteriaRepository.existsByPosting_IdAndIsDeletedFalse(id)) {
            throw new AdminExceptions.PostingNotPublishableException(
                    "no eligibility criteria have been set");
        }
        if (!reapplicationPolicyRepository.existsByPosting_IdAndIsDeletedFalse(id)) {
            throw new AdminExceptions.PostingNotPublishableException(
                    "no re-application policy has been set");
        }
        if (documentRepository.countByPosting_IdAndDocTypeAndIsDeletedFalse(
                id, PostingDocumentType.JD) == 0) {
            throw new AdminExceptions.PostingNotPublishableException(
                    "no JD document has been attached");
        }
        if (posting.getApplicationDeadline() == null
                || !posting.getApplicationDeadline().isAfter(Instant.now())) {
            throw new AdminExceptions.PostingNotPublishableException(
                    "the application deadline is not in the future");
        }

        Instant now = Instant.now();
        posting.setStatus(PostingStatus.PUBLISHED);
        posting.setPublishedAt(now);
        posting.setPublishedBy(actor.userId());
        posting.setUpdatedBy(actor.userId());
        JobPosting saved = postingRepository.save(posting);

        if (saved.isNotifyOnPublish()) {
            outboxWriter.write(
                    DriveTopics.POSTING_PUBLISHED,
                    "PostingPublished",
                    "JobPosting",
                    saved.getId(),
                    String.valueOf(saved.getId()),
                    actor.userId(),
                    new PostingPublishedEvent(
                            saved.getId(),
                            company.getId(),
                            company.getName(),
                            saved.getTitle(),
                            saved.getJobType().name(),
                            saved.getApplicationDeadline(),
                            targets.stream()
                                    .map(t -> new PostingPublishedEvent.TargetRef(
                                            t.getCollegeId(), t.getDepartmentId(), t.getBatchYear(), t.getDegree()))
                                    .toList(),
                            actor.userId()));
        }

        log.info("Published posting id={} companyId={} targets={} deadline={} actorId={}",
                id, company.getId(), targetCollegeIds, saved.getApplicationDeadline(), actor.userId());
        return toResponse(saved);
    }

    /**
     * Queues eligibility resolution for a freshly published drive.
     *
     * Called from the controller AFTER publish has committed, never from inside it:
     * the async worker reads the posting in its own transaction and would otherwise
     * find it still in DRAFT.
     *
     * Swallows its own failures on purpose. Publishing succeeded and the drive is
     * live; a resolution that could not start must not turn that into an error the
     * coordinator reads as "publish failed". It is recoverable at any time through
     * the re-evaluate endpoint, and the FAILED run row records what happened.
     */
    public void startEligibilityResolution(UserPrincipal actor, Long postingId) {
        try {
            var run = resolutionService.createRun(postingId, EligibilityRunReason.PUBLISH, actor.userId());
            runLauncher.launch(run.getId(), postingId, actor.userId());
            log.info("Eligibility resolution queued on publish: postingId={} runId={} actorId={}",
                    postingId, run.getId(), actor.userId());
        } catch (Exception e) {
            log.error("Posting {} published but eligibility resolution could not be queued: {}. "
                            + "Use the re-evaluate endpoint to retry.",
                    postingId, e.getMessage(), e);
        }
    }

    /** Manual early close. The scheduled sweep does the same thing at the deadline. */
    @Transactional
    public JobPostingResponse close(UserPrincipal actor, Long id, String reason) {
        try {
            return doClose(actor, id, reason);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to close posting id={} actorId={}: {}", id, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not close the drive. Please try again.");
        }
    }

    private JobPostingResponse doClose(UserPrincipal actor, Long id, String reason) {
        JobPosting posting = postingRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", id));
        assertActorCoversTargets(actor, id);

        if (!PostingStatusTransitions.canTransition(posting.getStatus(), PostingStatus.APPLICATIONS_CLOSED)) {
            throw new AdminExceptions.IllegalStatusTransitionException(
                    "JobPosting", posting.getStatus().name(), PostingStatus.APPLICATIONS_CLOSED.name());
        }
        posting.setStatus(PostingStatus.APPLICATIONS_CLOSED);
        posting.setApplicationsClosedAt(Instant.now());
        posting.setClosureReason(reason);
        posting.setUpdatedBy(actor.userId());

        log.info("Closed posting id={} early, reason={} actorId={}", id, reason, actor.userId());
        return toResponse(postingRepository.save(posting));
    }

    @Transactional
    public JobPostingResponse cancel(UserPrincipal actor, Long id, String reason) {
        try {
            return doCancel(actor, id, reason);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to cancel posting id={} actorId={}: {}", id, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not cancel the drive. Please try again.");
        }
    }

    private JobPostingResponse doCancel(UserPrincipal actor, Long id, String reason) {
        JobPosting posting = postingRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", id));
        assertActorCoversTargets(actor, id);

        if (!PostingStatusTransitions.canTransition(posting.getStatus(), PostingStatus.CANCELLED)) {
            throw new AdminExceptions.IllegalStatusTransitionException(
                    "JobPosting", posting.getStatus().name(), PostingStatus.CANCELLED.name());
        }
        if (reason == null || reason.isBlank()) {
            throw new AdminExceptions.InvalidRequestException(
                    "A reason is required when cancelling a drive -- students and the company have already been told about it.");
        }
        posting.setStatus(PostingStatus.CANCELLED);
        posting.setCancelledAt(Instant.now());
        posting.setClosureReason(reason);
        posting.setUpdatedBy(actor.userId());

        log.warn("Cancelled posting id={} reason={} actorId={}", id, reason, actor.userId());
        return toResponse(postingRepository.save(posting));
    }

    /**
     * Attach a document that is already in object storage. Publishing refuses a
     * posting with no JD attached, so this is a required step rather than a
     * convenience -- a drive students can apply to without being able to read
     * what they are applying for is not a drive.
     */
    @Transactional
    public PostingDocumentResponse addDocument(UserPrincipal actor, Long postingId, PostingDocumentRequest req) {
        try {
            return doAddDocument(actor, postingId, req);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to attach document to posting id={} actorId={}: {}",
                    postingId, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not attach the document. Please try again.");
        }
    }

    private PostingDocumentResponse doAddDocument(UserPrincipal actor, Long postingId, PostingDocumentRequest req) {
        if (!policy.canDraftPosting(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        JobPosting posting = requireEditable(postingId);

        JobPostingDocument doc = new JobPostingDocument();
        doc.setPosting(posting);
        doc.setDocType(req.docType());
        doc.setFileName(req.fileName().trim());
        doc.setStorageKey(req.storageKey().trim());
        doc.setContentType(req.contentType());
        doc.setSizeBytes(req.sizeBytes());
        doc.setPrimary(Boolean.TRUE.equals(req.primary()));
        doc.setUploadedBy(actor.userId());
        doc.setCreatedBy(actor.userId());
        doc.setUpdatedBy(actor.userId());

        JobPostingDocument saved = documentRepository.save(doc);
        log.info("Attached {} document id={} to posting id={} actorId={}",
                req.docType(), saved.getId(), postingId, actor.userId());
        return toResponse(saved);
    }

    public List<PostingDocumentResponse> listDocuments(Long postingId) {
        try {
            return documentRepository.findByPosting_IdAndIsDeletedFalse(postingId)
                    .stream().map(this::toResponse).toList();
        } catch (Exception e) {
            log.error("Failed to list documents for posting id={}: {}", postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load the posting's documents.");
        }
    }

    private PostingDocumentResponse toResponse(JobPostingDocument d) {
        return new PostingDocumentResponse(
                d.getId(), d.getPosting().getId(), d.getDocType(), d.getFileName(),
                d.getStorageKey(), d.getContentType(), d.getSizeBytes(), d.isPrimary(),
                d.getUploadedBy(), d.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public JobPostingResponse get(Long id) {
        try {
            return toResponse(postingRepository.findByIdWithCompany(id)
                    .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", id)));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load posting id={}: {}", id, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load the posting.");
        }
    }

    @Transactional(readOnly = true)
    public Page<JobPostingResponse> list(PostingStatus status, Long companyId, Pageable pageable) {
        try {
            return doList(status, companyId, pageable);
        } catch (Exception e) {
            log.error("Failed to list postings status={} companyId={}: {}", status, companyId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load the drive list.");
        }
    }

    /**
     * One page of drives, at a fixed query cost.
     *
     * The company arrives join-fetched with the page, and the skills, locations and
     * targets for every row on the page are loaded in one query each and indexed by
     * posting id. Five queries for a page of any size.
     *
     * The obvious version -- page.map(this::toResponse) -- costs four queries per
     * row, so a 50-row page becomes 200. It also threw LazyInitializationException
     * on the company, because this method had no transaction and open-in-view is
     * off, which is how the drive list came to fail outright.
     */
    private Page<JobPostingResponse> doList(PostingStatus status, Long companyId, Pageable pageable) {
        Page<JobPosting> page;
        if (companyId != null) {
            page = postingRepository.findByCompanyWithCompany(companyId, pageable);
        } else if (status != null) {
            page = postingRepository.findByStatusWithCompany(status, pageable);
        } else {
            page = postingRepository.findAllWithCompany(pageable);
        }
        if (page.isEmpty()) {
            // Skip the three batch queries entirely rather than issuing them with
            // an empty IN list.
            return new PageImpl<>(List.of(), pageable, page.getTotalElements());
        }

        List<Long> ids = new ArrayList<>(page.getNumberOfElements());
        for (JobPosting p : page.getContent()) {
            ids.add(p.getId());
        }

        Map<Long, List<JobPostingResponse.SkillResponse>> skillsByPosting = new HashMap<>();
        for (PostingSkill s : skillRepository.findByPosting_IdInAndIsDeletedFalse(ids)) {
            skillsByPosting.computeIfAbsent(s.getPosting().getId(), k -> new ArrayList<>())
                    .add(new JobPostingResponse.SkillResponse(s.getId(), s.getSkill(), s.isMandatory()));
        }

        Map<Long, List<JobPostingResponse.LocationResponse>> locationsByPosting = new HashMap<>();
        for (PostingLocation l : locationRepository.findByPosting_IdInAndIsDeletedFalse(ids)) {
            locationsByPosting.computeIfAbsent(l.getPosting().getId(), k -> new ArrayList<>())
                    .add(new JobPostingResponse.LocationResponse(
                            l.getId(), l.getCity(), l.getState(), l.getCountry()));
        }

        Map<Long, List<JobPostingResponse.TargetResponse>> targetsByPosting = new HashMap<>();
        for (PostingTarget tg : targetRepository.findByPosting_IdInAndIsDeletedFalse(ids)) {
            targetsByPosting.computeIfAbsent(tg.getPosting().getId(), k -> new ArrayList<>())
                    .add(new JobPostingResponse.TargetResponse(
                            tg.getId(), tg.getCollegeId(), tg.getDepartmentId(),
                            tg.getBatchYear(), tg.getDegree()));
        }

        return page.map(p -> toResponse(
                p,
                skillsByPosting.getOrDefault(p.getId(), List.of()),
                locationsByPosting.getOrDefault(p.getId(), List.of()),
                targetsByPosting.getOrDefault(p.getId(), List.of())));
    }

    // --- helpers ---

    /**
     * "c:{collegeId}|d:{deptId|*}|b:{year}|g:{degree|*}"
     *
     * Collapses the nullable target tuple into one non-null string so
     * uk_posting_target_key actually prevents duplicates. A unique constraint
     * over the raw columns would not: Postgres treats NULL as distinct from
     * NULL, so two identical "all departments" rows would both be accepted.
     */
    static String targetKey(Long collegeId, Long departmentId, Integer batchYear, String degree) {
        return "c:" + collegeId
                + "|d:" + (departmentId == null ? "*" : departmentId)
                + "|b:" + batchYear
                + "|g:" + (degree == null || degree.isBlank() ? "*" : degree.trim());
    }

    private JobPosting requireEditable(Long id) {
        JobPosting posting = postingRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", id));
        if (!PostingStatusTransitions.isEditable(posting.getStatus())) {
            throw new AdminExceptions.PostingNotEditableException(posting.getStatus().name());
        }
        return posting;
    }

    private void assertActorCoversTargets(UserPrincipal actor, Long postingId) {
        List<Long> collegeIds = targetRepository.findByPosting_IdAndIsDeletedFalse(postingId)
                .stream().map(PostingTarget::getCollegeId).distinct().toList();
        if (!policy.canCancelPosting(actor, collegeIds)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
    }

    private void apply(JobPosting p, JobPostingRequest req) {
        p.setTitle(req.title().trim());
        p.setJobType(req.jobType());
        p.setWorkMode(req.workMode());
        p.setDescription(req.description());
        p.setResponsibilities(req.responsibilities());
        p.setCtcMin(req.ctcMin());
        p.setCtcMax(req.ctcMax());
        if (req.ctcCurrency() != null && !req.ctcCurrency().isBlank()) {
            p.setCtcCurrency(req.ctcCurrency());
        }
        p.setPackageBasis(req.packageBasis() == null ? PackageBasis.CTC : req.packageBasis());
        p.setStipendAmount(req.stipendAmount());
        p.setStipendPeriod(req.stipendPeriod());
        p.setInternshipDurationMonths(req.internshipDurationMonths());
        p.setPpoPossible(Boolean.TRUE.equals(req.ppoPossible()));
        p.setBondMonths(req.bondMonths());
        p.setBondAmount(req.bondAmount());
        p.setVacancyCount(req.vacancyCount());
        p.setAcademicYear(req.academicYear().trim());
        p.setApplicationOpensAt(req.applicationOpensAt());
        p.setApplicationDeadline(req.applicationDeadline());
        p.setDriveDate(req.driveDate());
        p.setDriveVenue(req.driveVenue());
        p.setShortlistingMode(req.shortlistingMode() == null
                ? ShortlistingMode.COMPOSITE_SCORE : req.shortlistingMode());
        if (req.marksStudentPlaced() != null) {
            p.setMarksStudentPlaced(req.marksStudentPlaced());
        }
        p.setMaxApplications(req.maxApplications());
        if (req.notifyOnPublish() != null) {
            p.setNotifyOnPublish(req.notifyOnPublish());
        }
    }

    private void replaceSkills(JobPosting posting, JobPostingRequest req, UserPrincipal actor) {
        if (req.skills() == null) {
            return;
        }
        skillRepository.deleteByPosting_Id(posting.getId());
        skillRepository.flush();
        for (JobPostingRequest.SkillRequest s : req.skills()) {
            PostingSkill skill = new PostingSkill();
            skill.setPosting(posting);
            skill.setSkill(s.skill().trim());
            skill.setMandatory(Boolean.TRUE.equals(s.mandatory()));
            skill.setCreatedBy(actor.userId());
            skill.setUpdatedBy(actor.userId());
            skillRepository.save(skill);
        }
    }

    private void replaceLocations(JobPosting posting, JobPostingRequest req, UserPrincipal actor) {
        if (req.locations() == null) {
            return;
        }
        locationRepository.deleteByPosting_Id(posting.getId());
        locationRepository.flush();
        for (JobPostingRequest.LocationRequest l : req.locations()) {
            PostingLocation location = new PostingLocation();
            location.setPosting(posting);
            location.setCity(l.city().trim());
            location.setState(l.state());
            if (l.country() != null && !l.country().isBlank()) {
                location.setCountry(l.country());
            }
            location.setCreatedBy(actor.userId());
            location.setUpdatedBy(actor.userId());
            locationRepository.save(location);
        }
    }

    /** Single-posting path: three extra queries, which is fine for one row. */
    private JobPostingResponse toResponse(JobPosting p) {
        List<JobPostingResponse.SkillResponse> skills =
                skillRepository.findByPosting_IdAndIsDeletedFalse(p.getId()).stream()
                        .map(s -> new JobPostingResponse.SkillResponse(s.getId(), s.getSkill(), s.isMandatory()))
                        .toList();
        List<JobPostingResponse.LocationResponse> locations =
                locationRepository.findByPosting_IdAndIsDeletedFalse(p.getId()).stream()
                        .map(l -> new JobPostingResponse.LocationResponse(
                                l.getId(), l.getCity(), l.getState(), l.getCountry()))
                        .toList();
        List<JobPostingResponse.TargetResponse> targets =
                targetRepository.findByPosting_IdAndIsDeletedFalse(p.getId()).stream()
                        .map(t -> new JobPostingResponse.TargetResponse(
                                t.getId(), t.getCollegeId(), t.getDepartmentId(), t.getBatchYear(), t.getDegree()))
                        .toList();
        return toResponse(p, skills, locations, targets);
    }

    /** List path: children are supplied pre-batched, so this issues no queries. */
    private JobPostingResponse toResponse(JobPosting p,
                                          List<JobPostingResponse.SkillResponse> skills,
                                          List<JobPostingResponse.LocationResponse> locations,
                                          List<JobPostingResponse.TargetResponse> targets) {
        return new JobPostingResponse(
                p.getId(), p.getCompany().getId(), p.getCompany().getName(),
                p.getPostingCode(), p.getTitle(), p.getJobType(), p.getWorkMode(),
                p.getDescription(), p.getResponsibilities(),
                p.getCtcMin(), p.getCtcMax(), p.getCtcCurrency(), p.getPackageBasis(),
                p.getStipendAmount(), p.getStipendPeriod(), p.getInternshipDurationMonths(),
                p.isPpoPossible(), p.getBondMonths(), p.getBondAmount(), p.getVacancyCount(),
                p.getAcademicYear(), p.getStatus(),
                p.getApplicationOpensAt(), p.getApplicationDeadline(), p.getApplicationsClosedAt(),
                p.getPublishedAt(), p.getPublishedBy(),
                p.getDriveDate(), p.getDriveVenue(),
                p.getShortlistingMode(), p.isMarksStudentPlaced(),
                p.getMaxApplications(), p.isNotifyOnPublish(),
                skills, locations, targets,
                PostingStatusTransitions.isEditable(p.getStatus()),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}

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
import org.springframework.web.multipart.MultipartFile;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.core.storage.FileStorageService;
import org.yuktisetu.core.storage.StoredFile;
import org.yuktisetu.institute.db.College;
import org.yuktisetu.drive.db.Company;
import org.yuktisetu.institute.db.Department;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.JobPostingDocument;
import org.yuktisetu.drive.db.PostingLocation;
import org.yuktisetu.drive.db.PostingSkill;
import org.yuktisetu.drive.db.PostingTarget;
import org.yuktisetu.messaging.events.DriveTopics;
import org.yuktisetu.messaging.events.PostingPublishedEvent;
import org.yuktisetu.drive.model.ActorKind;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.ApplicationStatusTransitions;
import org.yuktisetu.drive.model.CompanyStatus;
import org.yuktisetu.drive.model.EligibilityRunReason;
import org.yuktisetu.drive.model.PackageBasis;
import org.yuktisetu.drive.model.PostingDocumentType;
import org.yuktisetu.drive.model.PostingStatus;
import org.yuktisetu.drive.model.PostingStatusTransitions;
import org.yuktisetu.drive.model.ShortlistingMode;
import org.yuktisetu.institute.repository.CollegeRepository;
import org.yuktisetu.drive.repository.CompanyRepository;
import org.yuktisetu.institute.db.Degree;
import org.yuktisetu.institute.repository.DegreeRepository;
import org.yuktisetu.institute.repository.DepartmentRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingDocumentRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PostingEligibilityCriteriaRepository;
import org.yuktisetu.drive.repository.PostingLocationRepository;
import org.yuktisetu.drive.repository.PostingReapplicationPolicyRepository;
import org.yuktisetu.drive.repository.PostingSkillRepository;
import org.yuktisetu.drive.repository.PostingTargetRepository;

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
    private final FileStorageService storage;
    private final CompanyRepository companyRepository;
    private final CollegeRepository collegeRepository;
    private final DepartmentRepository departmentRepository;
    private final DegreeRepository degreeRepository;
    private final PostingTargetRepository targetRepository;
    private final PostingSkillRepository skillRepository;
    private final PostingLocationRepository locationRepository;
    private final PostingEligibilityCriteriaRepository criteriaRepository;
    private final PostingReapplicationPolicyRepository reapplicationPolicyRepository;
    private final PostingScoringService scoringService;
    private final JobPostingDocumentRepository documentRepository;
    private final JobApplicationRepository applicationRepository;
    private final PlacementDrivePolicy policy;
    private final OutboxWriter outboxWriter;
    private final EligibilityResolutionService resolutionService;
    private final EligibilityRunLauncher runLauncher;
    private final ApplicantPoolService poolService;
    private final PostingCancellationCascade cancellationCascade;

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
        if (posting.getStatus() == PostingStatus.PUBLISHED
                && (req.applicationDeadline() == null || !req.applicationDeadline().isAfter(Instant.now()))) {
            // A live drive whose deadline is moved into the past would be sealed by
            // the sweep on its next tick, with nobody having decided to close it.
            throw new AdminExceptions.InvalidRequestException(
                    "The application deadline of a live drive must stay in the future. "
                            + "To stop the drive, cancel it instead.");
        }
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

            Degree degree = resolveTargetDegree(college, department, t.degreeId());

            String key = targetKey(t.collegeId(), t.departmentId(), t.batchYear(), t.degreeId());
            if (!seen.add(key)) {
                throw new AdminExceptions.DuplicateTargetException(key);
            }

            PostingTarget target = new PostingTarget();
            target.setPosting(posting);
            target.setCollege(college);
            target.setDepartment(department);
            target.setBatchYear(t.batchYear());
            target.setDegree(degree);
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
            // no weights, deadline in the past -- and the coordinator needs to read them
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

        List<PostingTarget> targets = requirePublishable(actor, posting);
        Company company = posting.getCompany();
        List<Long> targetCollegeIds = targets.stream().map(PostingTarget::getCollegeId).distinct().toList();

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
                                            t.getCollegeId(), t.getDepartmentId(), t.getBatchYear(),
                                            t.getDegree() == null ? null : t.getDegree().getName()))
                                    .toList(),
                            actor.userId()));
        }

        log.info("Published posting id={} companyId={} targets={} deadline={} actorId={}",
                id, company.getId(), targetCollegeIds, saved.getApplicationDeadline(), actor.userId());
        return toResponse(saved);
    }

    /**
     * Re-publish: apply the edits made to a LIVE drive.
     *
     * Edits to a published drive are saved as they are made, but nothing about who
     * is eligible changes until this runs. It re-checks everything publish checks,
     * then (after commit, from the controller) re-resolves eligibility. That
     * resolution disqualifies applicants who no longer qualify and tells everyone
     * who does that the drive was updated.
     */
    @Transactional
    public JobPostingResponse republish(UserPrincipal actor, Long id) {
        try {
            return doRepublish(actor, id);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to re-publish posting id={} actorId={}: {}", id, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not re-publish the drive. Please try again.");
        }
    }

    private JobPostingResponse doRepublish(UserPrincipal actor, Long id) {
        JobPosting posting = postingRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", id));
        if (posting.getStatus() != PostingStatus.PUBLISHED) {
            throw new AdminExceptions.PostingNotPublishableException(
                    "only a live (published) drive can be re-published; this one is " + posting.getStatus());
        }
        requirePublishable(actor, posting);

        posting.setUpdatedBy(actor.userId());
        JobPosting saved = postingRepository.save(posting);
        log.info("Re-published posting id={} deadline={} actorId={}",
                id, saved.getApplicationDeadline(), actor.userId());
        return toResponse(saved);
    }

    /** Queues the eligibility re-resolution that gives a re-publish its effect. */
    public void startRepublishResolution(UserPrincipal actor, Long postingId) {
        startResolution(actor, postingId, EligibilityRunReason.CRITERIA_CHANGED);
    }

    /**
     * Everything that must hold before a drive goes live, shared by publish and
     * re-publish so a live drive can never be edited into a state that publish
     * itself would have refused. Returns the targets it loaded.
     */
    private List<PostingTarget> requirePublishable(UserPrincipal actor, JobPosting posting) {
        Long id = posting.getId();
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
        if (posting.getShortlistingMode() == ShortlistingMode.COMPOSITE_SCORE
                && !scoringService.isConfigured(id)) {
            throw new AdminExceptions.PostingNotPublishableException(
                    "no scoring weights have been set");
        }
        if (posting.getApplicationDeadline() == null
                || !posting.getApplicationDeadline().isAfter(Instant.now())) {
            throw new AdminExceptions.PostingNotPublishableException(
                    "the application deadline is not in the future");
        }
        return targets;
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
        startResolution(actor, postingId, EligibilityRunReason.PUBLISH);
    }

    private void startResolution(UserPrincipal actor, Long postingId, EligibilityRunReason reason) {
        try {
            var run = resolutionService.createRun(postingId, reason, actor.userId());
            runLauncher.launch(run.getId(), postingId, actor.userId());
            log.info("Eligibility resolution queued ({}): postingId={} runId={} actorId={}",
                    reason, postingId, run.getId(), actor.userId());
        } catch (Exception e) {
            log.error("Posting {} saved but eligibility resolution could not be queued: {}. "
                            + "Use the re-evaluate endpoint to retry.",
                    postingId, e.getMessage(), e);
        }
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
        // findForSealing, not findByIdAndIsDeletedFalse: cancelling now bulk-updates
        // the application rows, so it must take the same exclusive lock the deadline
        // sweep takes. Otherwise an apply committing mid-cancel lands at APPLIED on
        // an already-cancelled drive and is stranded all over again.
        JobPosting posting = postingRepository.findForSealing(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", id));
        assertActorMayCancel(actor, id);

        if (!PostingStatusTransitions.canTransition(posting.getStatus(), PostingStatus.CANCELLED)) {
            throw new AdminExceptions.IllegalStatusTransitionException(
                    "JobPosting", posting.getStatus().name(), PostingStatus.CANCELLED.name());
        }
        if (reason == null || reason.isBlank()) {
            throw new AdminExceptions.InvalidRequestException(
                    "A reason is required when cancelling a drive -- students and the company have already been told about it.");
        }

        Instant now = Instant.now();
        posting.setStatus(PostingStatus.CANCELLED);
        posting.setCancelledAt(now);
        posting.setClosureReason(reason);
        posting.setUpdatedBy(actor.userId());
        postingRepository.save(posting);

        // Retire the applications with the drive. PostingStatus.CANCELLED is
        // terminal, so anything left at APPLIED / IN_REVIEW / SHORTLISTED here
        // could never be transitioned again by anything -- it would sit in the
        // student's "My Applications" as in-progress forever, against a drive that
        // no longer exists.
        //
        // History FIRST: recordBulkHistory reads from_status off the live row, so
        // it has to run before the UPDATE overwrites it.
        var cancellable = ApplicationStatusTransitions.cancellableStatuses();
        List<String> cancellableNames = cancellable.stream().map(Enum::name).toList();

        int audited = applicationRepository.recordBulkHistory(
                id, cancellableNames, ApplicationStatus.DRIVE_CANCELLED.name(),
                now, actor.userId(), ActorKind.TNP.name(),
                truncateReason("Drive cancelled: " + reason));

        int retired = applicationRepository.cancelPool(
                id, cancellable, ApplicationStatus.DRIVE_CANCELLED, actor.userId());

        if (audited != retired) {
            // Cannot happen inside one transaction holding the posting's write
            // lock, but if it ever does it means some rows changed status between
            // the two statements and the audit trail is incomplete -- which is
            // worth failing the cancel over rather than discovering in a dispute.
            throw new IllegalStateException(
                    "Audit/retire mismatch cancelling posting " + id
                            + ": audited=" + audited + " retired=" + retired);
        }

        // Everything else the drive had in flight: a running eligibility
        // resolution, an open scoring gate, notifications queued but not yet sent.
        // Must come after the applications are retired and before any new outbox
        // row is written, so it can never sweep up a message about the cancel itself.
        PostingCancellationCascade.Result stopped = cancellationCascade.stop(id, now, actor.userId());

        // TODO(notification): the applicants are now DRIVE_CANCELLED and nothing
        // tells them. Needs a driveCancelled() builder in Dispatches, a seeded
        // template, and a queueDriveCancelled() here -- all inside this
        // transaction, like every other dispatch in this service.

        log.warn("Cancelled posting id={} reason={} applicationsRetired={} runsStopped={} gatesCancelled={} "
                        + "queuedNotificationsDropped={} actorId={}",
                id, reason, retired, stopped.runsStopped(), stopped.gatesCancelled(),
                stopped.notificationsDropped(), actor.userId());
        // The bulk UPDATEs above clear the persistence context, so `posting` is detached
        // by now. Re-read it rather than answering from a stale instance.
        return toResponse(postingRepository.findByIdWithCompany(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", id)));
    }

    /** application_status_history.reason is varchar(255). */
    private static String truncateReason(String reason) {
        return reason.length() <= 255 ? reason : reason.substring(0, 252) + "...";
    }

    /**
     * Attach a document that is already in object storage. Optional since
     * 2026-10-20: the JD is parsed into the drive's own fields, which students
     * read on the drive page, so publishing no longer requires a file.
     */
    /**
     * Uploads a job-description file and attaches it to the posting in one call.
     *
     * Synchronous by design: the TnP is sitting in the drive builder waiting, so
     * the file goes to storage and the stored key comes straight back with the
     * response. The pre-existing addDocument(..., PostingDocumentRequest) stays
     * for the case where a key already exists -- this method is the front door
     * that produces one.
     *
     * The upload happens BEFORE the row is written. A failed upload then leaves
     * no document row pointing at a file that does not exist; the reverse order
     * would attach a broken link to a live drive.
     */
    @Transactional
    public PostingDocumentResponse uploadDocument(UserPrincipal actor, Long postingId,
                                                  PostingDocumentType docType,
                                                  MultipartFile file, Boolean primary) {
        try {
            if (!policy.canDraftPosting(actor)) {
                throw new AdminExceptions.InsufficientAuthorityException();
            }
            // Validated before the upload so an unauthorised or closed posting
            // never costs a round trip to the storage provider.
            requireEditable(postingId);

            StoredFile stored = storage.upload(file, "postings/" + postingId + "/" + docType.name().toLowerCase());

            log.info("JD document uploaded: postingId={} type={} key={} bytes={} actorId={}",
                    postingId, docType, stored.storageKey(), stored.sizeBytes(), actor.userId());

            return doAddDocument(actor, postingId, new PostingDocumentRequest(
                    docType,
                    stored.originalFileName(),
                    stored.storageKey(),
                    stored.url(),
                    stored.contentType(),
                    stored.sizeBytes(),
                    primary));

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to upload document for posting id={} actorId={}: {}",
                    postingId, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not upload the document. Please try again.");
        }
    }

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
        doc.setUrl(req.url());
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
                d.getStorageKey(), d.getUrl(), d.getContentType(), d.getSizeBytes(), d.isPrimary(),
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
                            tg.getBatchYear(), tg.getDegreeId(),
                            tg.getDegree() == null ? null : tg.getDegree().getName()));
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
    static String targetKey(Long collegeId, Long departmentId, Integer batchYear, Long degreeId) {
        return "c:" + collegeId
                + "|d:" + (departmentId == null ? "*" : departmentId)
                + "|b:" + batchYear
                + "|g:" + (degreeId == null ? "*" : degreeId);
    }

    /**
     * A target's degree must be real, offered by the target college, and -- when a
     * department is also named -- the degree that department belongs to. Otherwise
     * the drive would silently target nobody ("B.Tech students of an MCA
     * department") and no one would find out until the pool came back empty.
     */
    private Degree resolveTargetDegree(College college, Department department, Long degreeId) {
        if (degreeId == null) {
            return null;
        }
        Degree degree = degreeRepository.findByIdAndIsDeletedFalse(degreeId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Degree", degreeId));
        if (college.getDegrees().stream().noneMatch(d -> d.getId().equals(degreeId))) {
            throw new AdminExceptions.InvalidRequestException(
                    college.getName() + " does not offer " + degree.getName() + ".");
        }
        if (department != null && !department.getDegree().getId().equals(degreeId)) {
            throw new AdminExceptions.InvalidRequestException(
                    "Department " + department.getName() + " belongs to " + department.getDegree().getName()
                            + ", not " + degree.getName() + ".");
        }
        return degree;
    }

    private JobPosting requireEditable(Long id) {
        JobPosting posting = postingRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", id));
        if (!PostingEditing.isEditable(posting.getStatus())) {
            throw new AdminExceptions.PostingNotEditableException(posting.getStatus().name());
        }
        return posting;
    }

    /**
     * A drive can be cancelled at ANY stage, so a draft that has no targets yet
     * must be cancellable too. With no targets there are no colleges to check
     * scope against, and anyone allowed to author a drive may discard it.
     */
    private void assertActorMayCancel(UserPrincipal actor, Long postingId) {
        if (targetRepository.countByPosting_IdAndIsDeletedFalse(postingId) == 0) {
            if (!policy.canDraftPosting(actor)) {
                throw new AdminExceptions.InsufficientAuthorityException();
            }
            return;
        }
        assertActorCoversTargets(actor, postingId);
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
        ShortlistingMode mode = req.shortlistingMode() == null
                ? ShortlistingMode.COMPOSITE_SCORE : req.shortlistingMode();
        p.setShortlistingMode(mode);
        if (req.marksStudentPlaced() != null) {
            p.setMarksStudentPlaced(req.marksStudentPlaced());
        }
        p.setMaxApplications(req.maxApplications());

        // An FCFS drive with no seat count is a cap of nothing -- it would silently
        // accept unlimited applicants on a drive whose whole point is that seats
        // are finite, and nobody would find out until the room was overfull.
        if (mode.capsApplicationsAtApplyTime() && req.maxApplications() == null) {
            throw new AdminExceptions.InvalidRequestException(
                    "A first-come-first-served drive needs a seat count: set maxApplications. "
                            + "Under any other shortlisting mode the seat count is an optional "
                            + "post-deadline target instead.");
        }

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
    /**
     * The company's name without assuming the posting is still attached to a session.
     *
     * posting.getCompany() is a lazy proxy. Inside the session that loaded it,
     * getName() initialises it; on a detached posting it throws
     * LazyInitializationException, and a cancel did exactly that: its bulk UPDATEs
     * clear the persistence context, then building the response touched the proxy
     * and the whole cancel rolled back. If the proxy cannot be read, ask for the name
     * by id instead -- a one-row lookup in a fresh session.
     */
    private String companyNameOf(JobPosting p) {
        Company company = p.getCompany();
        if (org.hibernate.Hibernate.isInitialized(company)) {
            return company.getName();
        }
        try {
            return company.getName();
        } catch (org.hibernate.LazyInitializationException e) {
            return companyRepository.findById(company.getId()).map(Company::getName).orElse(null);
        }
    }

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
                                t.getId(), t.getCollegeId(), t.getDepartmentId(), t.getBatchYear(),
                                t.getDegreeId(), t.getDegree() == null ? null : t.getDegree().getName()))
                        .toList();
        return toResponse(p, skills, locations, targets);
    }

    /** List path: children are supplied pre-batched, so this issues no queries. */
    private JobPostingResponse toResponse(JobPosting p,
                                          List<JobPostingResponse.SkillResponse> skills,
                                          List<JobPostingResponse.LocationResponse> locations,
                                          List<JobPostingResponse.TargetResponse> targets) {
        return new JobPostingResponse(
                p.getId(), p.getCompany().getId(), companyNameOf(p),
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
                p.getMaxApplications(), p.getShortlistingMode().notice(), p.isNotifyOnPublish(),
                skills, locations, targets,
                PostingEditing.isEditable(p.getStatus()),
                p.getCreatedAt(), p.getUpdatedAt(),
                p.getReopenedAt(), p.getReopenCount(), p.getShortlistPublishedAt());
    }
}

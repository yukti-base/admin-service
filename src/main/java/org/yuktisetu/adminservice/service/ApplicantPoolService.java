package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.dto.ApplicantResponse;
import org.yuktisetu.adminservice.dto.EligibilityRunResponse;
import org.yuktisetu.adminservice.dto.ManualApplicantRequest;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.policy.PlacementDrivePolicy;
import org.yuktisetu.core.cache.CacheNames;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.ApplicationStatusHistory;
import org.yuktisetu.drive.db.EligibilityRun;
import org.yuktisetu.drive.db.JobApplication;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.PostingTarget;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.drive.model.ActorKind;
import org.yuktisetu.drive.model.ApplicationSource;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.EligibilityRunReason;
import org.yuktisetu.drive.repository.ApplicationStatusHistoryRepository;
import org.yuktisetu.drive.repository.EligibilityRunRepository;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PostingTargetRepository;
import org.yuktisetu.identity.repository.UserRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The TnP's view of, and control over, who is in a drive.
 *
 * Moved here from drive-service: administrative work must never queue behind a
 * service that is busy serving students during a drive.
 *
 * Every method starts from the drive's target colleges, because that set decides
 * whether this actor may see the pool at all -- an applicant list is student PII
 * across potentially several PCET colleges.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicantPoolService {

    private final JobPostingRepository postingRepository;
    private final PostingTargetRepository targetRepository;
    private final JobApplicationRepository applicationRepository;
    private final ApplicationStatusHistoryRepository historyRepository;
    private final EligibilityRunRepository runRepository;
    private final UserRepository userRepository;
    private final PlacementDrivePolicy policy;
    private final EligibilityResolutionService resolutionService;
    private final EligibilityRunLauncher runLauncher;
    private final ObjectMapper objectMapper;

    /**
     * One page of applicants, assembled from a single joined query.
     *
     * The alternative -- loading JobApplication entities and walking to the user
     * and profile per row -- fires four or five extra SELECTs per applicant, so a
     * 50-row page becomes 250 queries. This is one.
     */
    @Transactional(readOnly = true)
    public Page<ApplicantResponse> listApplicants(UserPrincipal actor, Long postingId,
                                                  ApplicationStatus status, Pageable pageable) {
        try {
            assertCanView(actor, postingId);

            int pageSize = pageable.getPageSize();
            long offset = pageable.getOffset();

            List<Object[]> rows = status == null
                    ? applicationRepository.findApplicantRows(postingId, pageSize, offset)
                    : applicationRepository.findApplicantRowsByStatus(postingId, status.name(), pageSize, offset);

            List<ApplicantResponse> content = new ArrayList<>(rows.size());
            for (Object[] r : rows) {
                content.add(mapApplicantRow(r));
            }

            long total = status == null
                    ? applicationRepository.countByPosting_IdAndIsDeletedFalse(postingId)
                    : applicationRepository.countByPosting_IdAndStatusAndIsDeletedFalse(postingId, status);

            return new PageImpl<>(content, pageable, total);

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list applicants for postingId={} actorId={}: {}",
                    postingId, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not load the applicant list. Please try again.");
        }
    }

    /** Status counts for the pool header, in one grouped query rather than one per tab. */
    @Transactional(readOnly = true)
    public Map<String, Long> statusCounts(UserPrincipal actor, Long postingId) {
        try {
            assertCanView(actor, postingId);
            Map<String, Long> counts = new HashMap<>();
            for (Object[] row : applicationRepository.countByStatusGrouped(postingId)) {
                counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
            }
            return counts;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to count applicants for postingId={}: {}", postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load applicant counts.");
        }
    }

    @Transactional(readOnly = true)
    public List<EligibilityRunResponse> listRuns(UserPrincipal actor, Long postingId) {
        try {
            assertCanView(actor, postingId);
            List<EligibilityRun> runs = runRepository.findByPosting_IdOrderByStartedAtDesc(postingId);
            List<EligibilityRunResponse> out = new ArrayList<>(runs.size());
            for (EligibilityRun run : runs) {
                out.add(toRunResponse(run));
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list eligibility runs for postingId={}: {}", postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load eligibility runs.");
        }
    }

    /**
     * Start a re-resolution and return immediately.
     *
     * Deliberately does not wait: resolving a cohort is a heavy batch job, and the
     * coordinator's screen should show a RUNNING row it can poll rather than a
     * spinner held open for the length of the computation. Not @Transactional -- the
     * run row must be committed by createRun before the async worker can see it.
     */
    public EligibilityRunResponse reEvaluate(UserPrincipal actor, Long postingId) {
        try {
            List<Long> collegeIds = targetCollegeIds(postingId);
            if (!policy.canManagePool(actor, collegeIds)) {
                throw new AdminExceptions.InsufficientAuthorityException();
            }
            EligibilityRun run = resolutionService.createRun(
                    postingId, EligibilityRunReason.MANUAL, actor.userId());
            runLauncher.launch(run.getId(), postingId, actor.userId());

            log.info("Eligibility re-evaluation queued: postingId={} runId={} actorId={}",
                    postingId, run.getId(), actor.userId());
            return toRunResponse(run);

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to queue re-evaluation for postingId={}: {}", postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not start eligibility resolution. Please try again.");
        }
    }

    /**
     * Add applicants by hand -- single or bulk through the same shape.
     *
     * BYPASSES the eligibility gate by design: this is how the TnP includes a
     * student the system filtered out, or records a candidate the company
     * shortlisted outside the portal. Everything created here is stamped TNP_ADDED
     * with a mandatory reason on the history row, so a row that skipped the gate is
     * never indistinguishable from one that cleared it.
     *
     * Permitted after the deadline too, which is the point: a student added after
     * shortlisting is by definition already shortlisted.
     *
     * Batched throughout -- one query for all the users, one for the existing
     * applications, then a single saveAll. No query runs inside the loop.
     */
    @Transactional
    public List<ApplicantResponse> addManually(UserPrincipal actor, Long postingId,
                                               ManualApplicantRequest request) {
        try {
            List<Long> collegeIds = targetCollegeIds(postingId);
            if (!policy.canManagePool(actor, collegeIds)) {
                throw new AdminExceptions.InsufficientAuthorityException();
            }
            JobPosting posting = postingRepository.findByIdAndIsDeletedFalse(postingId)
                    .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", postingId));

            // Distinct ids in a LinkedHashSet: de-duplicates a re-pasted list while
            // keeping the caller's order, and membership checks stay O(1).
            Set<Long> requested = new java.util.LinkedHashSet<>(request.studentUserIds());

            Map<Long, User> users = new HashMap<>(Math.max(16, requested.size() * 2));
            for (User u : userRepository.findAllById(requested)) {
                if (!u.isDeleted()) {
                    users.put(u.getId(), u);
                }
            }
            Set<Long> missing = new HashSet<>(requested);
            missing.removeAll(users.keySet());
            if (!missing.isEmpty()) {
                throw new AdminExceptions.InvalidRequestException(
                        "No active user exists for id(s): " + missing);
            }

            Map<Long, JobApplication> existing = new HashMap<>(Math.max(16, requested.size() * 2));
            for (JobApplication a : applicationRepository
                    .findByPosting_IdAndStudent_IdInAndIsDeletedFalse(postingId, requested)) {
                existing.put(a.getStudentUserId(), a);
            }

            Instant now = Instant.now();
            List<JobApplication> toSave = new ArrayList<>(requested.size());
            List<ApplicationStatusHistory> historyRows = new ArrayList<>(requested.size());
            List<ApplicantResponse> result = new ArrayList<>(requested.size());

            for (Long studentUserId : requested) {
                JobApplication application = existing.get(studentUserId);
                ApplicationStatus from = application == null ? null : application.getStatus();

                if (from == ApplicationStatus.APPLIED) {
                    // Already in the pool. A no-op rather than an error, so a
                    // re-uploaded list does not fail halfway through.
                    result.add(toApplicant(application, users.get(studentUserId)));
                    continue;
                }
                if (application == null) {
                    application = new JobApplication();
                    application.setPosting(posting);
                    application.setStudent(users.get(studentUserId));
                    application.setCreatedBy(actor.userId());
                }
                application.setStatus(ApplicationStatus.APPLIED);
                application.setAppliedAt(now);
                application.setWithdrawnAt(null);
                application.setSource(ApplicationSource.TNP_ADDED);
                application.setUpdatedBy(actor.userId());

                toSave.add(application);
                historyRows.add(history(application, from, ApplicationStatus.APPLIED,
                        actor.userId(), ActorKind.TNP, request.reason()));
                result.add(toApplicant(application, users.get(studentUserId)));
            }

            if (!toSave.isEmpty()) {
                applicationRepository.saveAll(toSave);
                historyRepository.saveAll(historyRows);
            }

            log.info("Manually added {} applicant(s) to postingId={} actorId={} reason={}",
                    toSave.size(), postingId, actor.userId(), request.reason());
            return result;

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to add applicants manually to postingId={}: {}", postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not add the applicants. Please try again.");
        }
    }

    /** Remove an applicant from consideration. Reason mandatory -- this gets disputed. */
    @Transactional
    public ApplicantResponse disqualify(UserPrincipal actor, Long postingId,
                                        Long applicationId, String reason) {
        try {
            List<Long> collegeIds = targetCollegeIds(postingId);
            if (!policy.canDisqualify(actor, collegeIds)) {
                throw new AdminExceptions.InsufficientAuthorityException();
            }
            JobApplication application = applicationRepository.findById(applicationId)
                    .filter(a -> !a.isDeleted() && a.getPosting().getId().equals(postingId))
                    .orElseThrow(() -> new AdminExceptions.NotFoundException("JobApplication", applicationId));

            ApplicationStatus from = application.getStatus();
            application.setStatus(ApplicationStatus.DISQUALIFIED);
            application.setDisqualifiedAt(Instant.now());
            application.setDisqualifiedBy(actor.userId());
            application.setDisqualificationReason(reason);
            application.setUpdatedBy(actor.userId());
            applicationRepository.save(application);

            historyRepository.save(history(application, from, ApplicationStatus.DISQUALIFIED,
                    actor.userId(), ActorKind.TNP, reason));

            log.info("Applicant disqualified: applicationId={} postingId={} actorId={} reason={}",
                    applicationId, postingId, actor.userId(), reason);
            return toApplicant(application, application.getStudent());

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to disqualify applicationId={} on postingId={}: {}",
                    applicationId, postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not disqualify the applicant. Please try again.");
        }
    }

    /**
     * The colleges a posting targets, cached.
     *
     * Read on every single pool request purely to authorise it, and it cannot change
     * once a posting is published. Without the cache this is an extra query on every
     * page of every applicant list.
     */
    @Cacheable(cacheNames = CacheNames.POSTING_TARGET_COLLEGES, key = "#postingId")
    public List<Long> targetCollegeIds(Long postingId) {
        List<Long> ids = targetRepository.findByPosting_IdAndIsDeletedFalse(postingId)
                .stream().map(PostingTarget::getCollegeId).distinct().toList();
        if (ids.isEmpty()) {
            // Either the posting does not exist or it was never finished. Nothing to
            // authorise against, and failing closed is the only safe reading.
            throw new AdminExceptions.NotFoundException("JobPosting", postingId);
        }
        return ids;
    }

    @CacheEvict(cacheNames = CacheNames.POSTING_TARGET_COLLEGES, key = "#postingId")
    public void evictTargetColleges(Long postingId) {
        log.debug("Evicted cached target colleges for postingId={}", postingId);
    }

    private void assertCanView(UserPrincipal actor, Long postingId) {
        if (!policy.canViewPool(actor, targetCollegeIds(postingId))) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
    }

    private ApplicationStatusHistory history(JobApplication application, ApplicationStatus from,
                                             ApplicationStatus to, Long actorUserId,
                                             ActorKind actorKind, String reason) {
        ApplicationStatusHistory h = new ApplicationStatusHistory();
        h.setApplication(application);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setChangedAt(Instant.now());
        h.setChangedBy(actorUserId);
        h.setActorKind(actorKind);
        h.setReason(reason);
        h.setCreatedBy(actorUserId);
        h.setUpdatedBy(actorUserId);
        return h;
    }

    /** Maps the flat joined row. Column order is documented on the repository query. */
    private ApplicantResponse mapApplicantRow(Object[] r) {
        return new ApplicantResponse(
                num(r[0]), num(r[1]),
                fullName((String) r[2], (String) r[3]),
                (String) r[4], (String) r[5],
                num(r[6]), num(r[7]),
                r[8] == null ? null : ((Number) r[8]).intValue(),
                r[9] == null ? null : ((Number) r[9]).doubleValue(),
                r[10] == null ? null : ((Number) r[10]).intValue(),
                r[11] == null ? null : ApplicationStatus.valueOf(String.valueOf(r[11])),
                r[12] == null ? null : ApplicationSource.valueOf(String.valueOf(r[12])),
                toInstant(r[13]),
                (String) r[14],
                (String) r[15], (String) r[16]);
    }

    private ApplicantResponse toApplicant(JobApplication a, User u) {
        return new ApplicantResponse(
                a.getId(), a.getStudentUserId(),
                u == null ? null : fullName(u.getFirstName(), u.getLastName()),
                u == null ? null : u.getEmail(),
                u == null ? null : u.getPhone(),
                null, null, null, null, null,
                a.getStatus(), a.getSource(), a.getAppliedAt(), a.getDisqualificationReason(),
                a.getResumeUrl(), a.getResumeFileName());
    }

    private String fullName(String first, String last) {
        return last == null || last.isBlank() ? first : first + " " + last;
    }

    /**
     * The JDBC driver hands back java.time.Instant for a timestamptz column on
     * some paths and java.sql.Timestamp on others, so a straight cast to either
     * one blows up half the time. Handled explicitly rather than assumed.
     */
    private java.time.Instant toInstant(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof java.time.Instant instant) {
            return instant;
        }
        if (o instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (o instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return null;
    }

    private Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private EligibilityRunResponse toRunResponse(EligibilityRun run) {
        Map<String, Integer> summary = Map.of();
        try {
            if (run.getFailureSummary() != null && !run.getFailureSummary().isBlank()) {
                summary = objectMapper.readValue(run.getFailureSummary(),
                        new TypeReference<Map<String, Integer>>() {});
            }
        } catch (Exception e) {
            // A malformed summary must not hide the run itself from the TnP.
            log.warn("Could not parse failureSummary on eligibility run {}: {}", run.getId(), e.getMessage());
        }
        return new EligibilityRunResponse(
                run.getId(), run.getPosting().getId(), run.getRunReason(), run.getStatus(),
                run.getStartedAt(), run.getFinishedAt(),
                run.getCandidatesEvaluated(), run.getEligibleCount(), run.getIneligibleCount(),
                summary, run.getError());
    }
}

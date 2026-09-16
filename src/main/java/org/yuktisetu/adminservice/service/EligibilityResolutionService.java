package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.core.cache.CacheNames;
import org.yuktisetu.db.DrivePlacedStudentDecision;
import org.yuktisetu.db.EligibilityRun;
import org.yuktisetu.db.JobPosting;
import org.yuktisetu.db.PlacementOffer;
import org.yuktisetu.db.PostingEligibilityCriteria;
import org.yuktisetu.db.PostingEligibleStudent;
import org.yuktisetu.db.PostingReapplicationPolicy;
import org.yuktisetu.db.StudentProfile;
import org.yuktisetu.model.CandidateFacts;
import org.yuktisetu.model.CandidateFactsFactory;
import org.yuktisetu.model.CriteriaSnapshot;
import org.yuktisetu.model.CriteriaSnapshotFactory;
import org.yuktisetu.model.EligibilityEvaluator;
import org.yuktisetu.model.EligibilityOutcome;
import org.yuktisetu.model.EligibilityRunReason;
import org.yuktisetu.model.EligibilityRunStatus;
import org.yuktisetu.model.ReapplicationRules;
import org.yuktisetu.repository.DrivePlacedStudentDecisionRepository;
import org.yuktisetu.repository.EligibilityCandidateRepository;
import org.yuktisetu.repository.EligibilityRunRepository;
import org.yuktisetu.repository.JobPostingRepository;
import org.yuktisetu.repository.PlacementOfferRepository;
import org.yuktisetu.repository.PostingAptitudeCriterionRepository;
import org.yuktisetu.repository.PostingCustomCriterionRepository;
import org.yuktisetu.repository.PostingEligibilityCriteriaRepository;
import org.yuktisetu.repository.PostingEligibleStudentRepository;
import org.yuktisetu.repository.PostingReapplicationPolicyRepository;
import org.yuktisetu.repository.PostingSemesterCgpaCriterionRepository;
import org.yuktisetu.repository.PostingTargetRepository;
import org.yuktisetu.repository.UserRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves which students a posting is open to, and records why everyone else
 * missed out.
 *
 * Lives in admin-service, not drive-service: resolution is an administrative act
 * triggered by publishing or by a coordinator, and admins must never queue behind
 * a service that is busy serving students mid-drive.
 *
 * Runs on the HEAVY executor and is fire-and-forget. The caller gets an
 * EligibilityRun row in RUNNING state immediately and polls it, so the TnP's screen
 * never blocks on a cohort-sized computation.
 *
 * Performance shape, which is the whole point of this class:
 *
 *  - ONE targeting query, then ONE query each for profiles, dynamic-field values,
 *    live offers and admin decisions. Five queries total regardless of cohort size;
 *    nothing is fetched inside the candidate loop.
 *  - Everything is held in HashMaps keyed by student id, so each of the thousands
 *    of per-candidate lookups is O(1) rather than a scan of a list.
 *  - The criteria snapshot is built once and reused, not rebuilt per student.
 *  - Eligible rows are collected and saved in one batch insert.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EligibilityResolutionService {

    /** Chunk size for the batch insert of eligible rows. */
    private static final int INSERT_BATCH = 500;

    private final JobPostingRepository postingRepository;
    private final PostingEligibilityCriteriaRepository criteriaRepository;
    private final PostingSemesterCgpaCriterionRepository semesterRepository;
    private final PostingAptitudeCriterionRepository aptitudeRepository;
    private final PostingCustomCriterionRepository customRepository;
    private final PostingReapplicationPolicyRepository policyRepository;
    private final PostingTargetRepository targetRepository;
    private final EligibilityCandidateRepository candidateRepository;
    private final PostingEligibleStudentRepository eligibleStudentRepository;
    private final EligibilityRunRepository runRepository;
    private final PlacementOfferRepository offerRepository;
    private final DrivePlacedStudentDecisionRepository decisionRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * Creates the run row and commits it, so the async worker and the caller both
     * see it. REQUIRES_NEW because the caller may already be inside a transaction
     * that has not committed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EligibilityRun createRun(Long postingId, EligibilityRunReason reason, Long actorUserId) {
        try {
            JobPosting posting = postingRepository.findByIdAndIsDeletedFalse(postingId)
                    .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", postingId));

            if (targetRepository.countByPosting_IdAndIsDeletedFalse(postingId) == 0) {
                throw new AdminExceptions.PostingNotPublishableException(
                        "it has no targets, so there is no audience to resolve");
            }
            if (!criteriaRepository.existsByPosting_IdAndIsDeletedFalse(postingId)) {
                throw new AdminExceptions.PostingNotPublishableException(
                        "no eligibility criteria have been set");
            }
            if (!policyRepository.existsByPosting_IdAndIsDeletedFalse(postingId)) {
                throw new AdminExceptions.PostingNotPublishableException(
                        "no re-application policy has been set");
            }

            EligibilityRun run = new EligibilityRun();
            run.setPosting(posting);
            run.setRunReason(reason);
            run.setStatus(EligibilityRunStatus.RUNNING);
            run.setStartedAt(Instant.now());
            run.setCreatedBy(actorUserId);
            run.setUpdatedBy(actorUserId);
            return runRepository.saveAndFlush(run);

        } catch (AdminExceptions.NotFoundException | AdminExceptions.PostingNotPublishableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create eligibility run for postingId={}: {}", postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not start eligibility resolution. Please try again.");
        }
    }

    /**
     * The resolution itself. Invoked from EligibilityRunLauncher rather than from
     * this class, so that @Transactional actually applies -- see that class.
     */
    @Transactional
    public void execute(Long runId, Long postingId, Long actorUserId) {
        EligibilityRun run = runRepository.findById(runId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("EligibilityRun", runId));
        JobPosting posting = postingRepository.findByIdAndIsDeletedFalse(postingId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", postingId));

        PostingEligibilityCriteria criteriaEntity = criteriaRepository
                .findByPosting_IdAndIsDeletedFalse(postingId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("PostingEligibilityCriteria", postingId));
        PostingReapplicationPolicy policy = policyRepository
                .findByPosting_IdAndIsDeletedFalse(postingId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("PostingReapplicationPolicy", postingId));

        // Built once, reused for every candidate.
        CriteriaSnapshot snapshot = snapshotFor(postingId, criteriaEntity);

        // --- Query 1: targeting. One call, whatever the cohort size. ---
        List<Object[]> targeted = candidateRepository.findTargetedStudents(postingId);
        if (targeted.isEmpty()) {
            finish(run, 0, 0, 0, Map.of());
            return;
        }

        // LinkedHashMap: O(1) lookups, and insertion order keeps runs reproducible
        // for anyone comparing two of them. Sized up front so it never rehashes.
        Map<Long, Long> targetByStudent = new HashMap<>(Math.max(16, targeted.size() * 2));
        List<Long> studentIds = new ArrayList<>(targeted.size());
        for (Object[] row : targeted) {
            Long studentUserId = ((Number) row[0]).longValue();
            Long targetId = ((Number) row[1]).longValue();
            // A student can match several targets (one degree-restricted, one not);
            // the first is enough to record why they were admitted.
            if (targetByStudent.putIfAbsent(studentUserId, targetId) == null) {
                studentIds.add(studentUserId);
            }
        }

        // --- Queries 2-5: everything the loop needs, batched. ---
        Map<Long, StudentProfile> profiles = new HashMap<>(Math.max(16, studentIds.size() * 2));
        for (StudentProfile p : candidateRepository.findProfilesByUserIds(studentIds)) {
            profiles.put(p.getUser().getId(), p);
        }

        Map<Long, Map<String, String>> customValues = loadCustomValues(snapshot, studentIds);

        Map<Long, List<PlacementOffer>> liveOffers = new HashMap<>();
        for (PlacementOffer o : offerRepository.findLiveOffersForStudents(
                studentIds, ReapplicationRules.liveStatuses())) {
            liveOffers.computeIfAbsent(o.getStudentUserId(), k -> new ArrayList<>(2)).add(o);
        }

        Map<Long, DrivePlacedStudentDecision> decisions = new HashMap<>();
        for (DrivePlacedStudentDecision d : decisionRepository.findByPosting_IdAndIsDeletedFalse(postingId)) {
            decisions.put(d.getStudentUserId(), d);
        }

        // --- Evaluate. No query inside this loop. ---
        eligibleStudentRepository.deleteAllForPosting(postingId);

        Map<String, Integer> failureSummary = new HashMap<>();
        List<PostingEligibleStudent> pending = new ArrayList<>(INSERT_BATCH);
        int eligibleCount = 0;
        int ineligibleCount = 0;

        for (Long studentUserId : studentIds) {
            StudentProfile profile = profiles.get(studentUserId);
            if (profile == null) {
                // The targeting query inner-joins student_profiles, so this means the
                // row vanished between the two queries. Counted, not fatal.
                tally(failureSummary, "PROFILE_MISSING");
                ineligibleCount++;
                continue;
            }
            Long targetId = targetByStudent.get(studentUserId);

            CandidateFacts facts = CandidateFactsFactory.of(
                    profile, targetId, null, null,
                    customValues.getOrDefault(studentUserId, Map.of()));

            EligibilityOutcome outcome = EligibilityEvaluator.evaluate(snapshot, facts);
            List<String> reapplicationFailures = ReapplicationRules.check(
                    posting, policy, liveOffers.get(studentUserId), decisions.get(studentUserId));

            if (outcome.eligible() && reapplicationFailures.isEmpty()) {
                PostingEligibleStudent row = new PostingEligibleStudent();
                row.setPosting(posting);
                // Proxy references: no extra SELECT per student just to set an FK.
                row.setStudent(userRepository.getReferenceById(studentUserId));
                row.setMatchedTarget(targetRepository.getReferenceById(targetId));
                row.setEvaluationRun(run);
                row.setEvaluatedAt(Instant.now());
                row.setCreatedBy(actorUserId);
                row.setUpdatedBy(actorUserId);
                pending.add(row);
                eligibleCount++;

                // Flush in chunks so a 100k-candidate cohort never holds every new
                // row in the persistence context at once.
                if (pending.size() >= INSERT_BATCH) {
                    eligibleStudentRepository.saveAll(pending);
                    eligibleStudentRepository.flush();
                    pending.clear();
                }
            } else {
                ineligibleCount++;
                for (String code : outcome.failureCodes()) {
                    tally(failureSummary, code);
                }
                for (String code : reapplicationFailures) {
                    tally(failureSummary, code);
                }
                if (outcome.needsReview()) {
                    tally(failureSummary, "NEEDS_REVIEW");
                }
            }
            for (String code : outcome.unevaluatedCodes()) {
                tally(failureSummary, code);
            }
        }
        if (!pending.isEmpty()) {
            eligibleStudentRepository.saveAll(pending);
        }

        finish(run, studentIds.size(), eligibleCount, ineligibleCount, failureSummary);
    }

    /**
     * The assembled criteria for a posting, cached.
     *
     * Read on every resolution and every apply-time re-check, and it changes only
     * while a posting is still a draft. Evicted explicitly when the TnP edits the
     * criteria; the cache TTL is only a backstop for a missed eviction.
     */
    @Cacheable(cacheNames = CacheNames.POSTING_CRITERIA, key = "#postingId")
    public CriteriaSnapshot snapshotFor(Long postingId, PostingEligibilityCriteria criteriaEntity) {
        Long criteriaId = criteriaEntity.getId();
        return CriteriaSnapshotFactory.of(
                criteriaEntity,
                semesterRepository.findByCriteria_IdAndIsDeletedFalse(criteriaId),
                aptitudeRepository.findByCriteria_IdAndIsDeletedFalse(criteriaId),
                customRepository.findByCriteria_IdAndIsDeletedFalse(criteriaId));
    }

    /** Called whenever criteria change, so the next resolution reads the new gate. */
    @CacheEvict(cacheNames = CacheNames.POSTING_CRITERIA, key = "#postingId")
    public void evictCriteria(Long postingId) {
        log.debug("Evicted cached criteria snapshot for postingId={}", postingId);
    }

    private Map<Long, Map<String, String>> loadCustomValues(CriteriaSnapshot snapshot, List<Long> studentIds) {
        List<String> keys = CriteriaSnapshotFactory.dynamicFieldKeys(snapshot);
        if (keys.isEmpty()) {
            // No dynamic-field criteria: skip the query rather than issue one with
            // an empty IN list.
            return Map.of();
        }
        Map<Long, Map<String, String>> byStudent = new HashMap<>();
        for (Object[] row : candidateRepository.findDynamicFieldValues(studentIds, keys)) {
            Long studentUserId = ((Number) row[0]).longValue();
            String fieldKey = (String) row[1];
            String value = firstNonNull(row[2], row[3], row[4], row[5]);
            if (value != null) {
                byStudent.computeIfAbsent(studentUserId, k -> new HashMap<>(4)).put(fieldKey, value);
            }
        }
        return byStudent;
    }

    /**
     * StudentDynamicFieldValue spreads its value across four typed columns, only
     * one of which is populated.
     */
    private String firstNonNull(Object... candidates) {
        for (Object c : candidates) {
            if (c != null) {
                return String.valueOf(c);
            }
        }
        return null;
    }

    private void tally(Map<String, Integer> summary, String code) {
        summary.merge(code, 1, Integer::sum);
    }

    private void finish(EligibilityRun run, int evaluated, int eligible, int ineligible,
                        Map<String, Integer> failureSummary) {
        run.setStatus(EligibilityRunStatus.COMPLETED);
        run.setFinishedAt(Instant.now());
        run.setCandidatesEvaluated(evaluated);
        run.setEligibleCount(eligible);
        run.setIneligibleCount(ineligible);
        run.setFailureSummary(failureSummary.isEmpty() ? null : objectMapper.writeValueAsString(failureSummary));
        runRepository.save(run);
        log.info("Eligibility run {} complete: postingId={} evaluated={} eligible={} ineligible={} reasons={}",
                run.getId(), run.getPosting().getId(), evaluated, eligible, ineligible, failureSummary);
    }

    /** Own transaction, so it still records the failure after the main one rolled back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long runId, Exception cause) {
        try {
            runRepository.findById(runId).ifPresent(run -> {
                run.setStatus(EligibilityRunStatus.FAILED);
                run.setFinishedAt(Instant.now());
                run.setError(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                runRepository.save(run);
            });
        } catch (Exception e) {
            log.error("Could not even mark eligibility run {} as FAILED: {}", runId, e.getMessage(), e);
        }
    }
}

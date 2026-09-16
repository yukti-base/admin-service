package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.dto.EligibilityCriteriaRequest;
import org.yuktisetu.adminservice.dto.EligibilityCriteriaResponse;
import org.yuktisetu.adminservice.dto.ReapplicationPolicyRequest;
import org.yuktisetu.adminservice.dto.ReapplicationPolicyResponse;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.policy.PlacementDrivePolicy;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.db.JobPosting;
import org.yuktisetu.db.PostingAptitudeCriterion;
import org.yuktisetu.db.PostingCustomCriterion;
import org.yuktisetu.db.PostingEligibilityCriteria;
import org.yuktisetu.db.PostingReapplicationPolicy;
import org.yuktisetu.db.PostingSemesterCgpaCriterion;
import org.yuktisetu.model.AlternativeGateMode;
import org.yuktisetu.model.CriterionSource;
import org.yuktisetu.model.MissingDataPolicy;
import org.yuktisetu.model.PostingStatusTransitions;
import org.yuktisetu.model.ReapplicationStance;
import org.yuktisetu.repository.DynamicFieldDefinitionRepository;
import org.yuktisetu.repository.JobPostingRepository;
import org.yuktisetu.repository.PostingAptitudeCriterionRepository;
import org.yuktisetu.repository.PostingCustomCriterionRepository;
import org.yuktisetu.repository.PostingEligibilityCriteriaRepository;
import org.yuktisetu.repository.PostingReapplicationPolicyRepository;
import org.yuktisetu.repository.PostingSemesterCgpaCriterionRepository;

import java.math.BigDecimal;
import java.util.List;

/**
 * The eligibility gate and the re-application policy for one posting.
 *
 * Both are replaced wholesale rather than patched, because they are read as a
 * unit by the evaluator and a partially-applied criteria set is an eligibility
 * bug that nobody sees until the wrong students are notified.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostingEligibilityService {

    private final JobPostingRepository postingRepository;
    private final PostingEligibilityCriteriaRepository criteriaRepository;
    private final PostingSemesterCgpaCriterionRepository semesterRepository;
    private final PostingAptitudeCriterionRepository aptitudeRepository;
    private final PostingCustomCriterionRepository customRepository;
    private final PostingReapplicationPolicyRepository policyRepository;
    private final DynamicFieldDefinitionRepository dynamicFieldDefinitionRepository;
    private final PlacementDrivePolicy drivePolicy;
    private final EligibilityResolutionService resolutionService;

    @Transactional
    public EligibilityCriteriaResponse setCriteria(UserPrincipal actor, Long postingId,
                                                   EligibilityCriteriaRequest req) {
        try {
            return doSetCriteria(actor, postingId, req);
        } catch (ApiException e) {
            // Criteria validation failures name the offending criterion, and the
            // coordinator needs that exact text to fix the form.
            throw e;
        } catch (Exception e) {
            log.error("Failed to set criteria on posting id={} actorId={}: {}",
                    postingId, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not save the eligibility criteria. Please try again.");
        }
    }

    private EligibilityCriteriaResponse doSetCriteria(UserPrincipal actor, Long postingId,
                                                      EligibilityCriteriaRequest req) {
        if (!drivePolicy.canDraftPosting(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        JobPosting posting = requireEditablePosting(postingId);
        validate(req);

        PostingEligibilityCriteria criteria = criteriaRepository
                .findByPosting_IdAndIsDeletedFalse(postingId)
                .orElseGet(() -> {
                    PostingEligibilityCriteria fresh = new PostingEligibilityCriteria();
                    fresh.setPosting(posting);
                    fresh.setCreatedBy(actor.userId());
                    return fresh;
                });

        criteria.setAggregateCgpaEnabled(Boolean.TRUE.equals(req.aggregateCgpaEnabled()));
        criteria.setMinAggregateCgpa(req.minAggregateCgpa());
        criteria.setSemesterCgpaEnabled(Boolean.TRUE.equals(req.semesterCgpaEnabled()));
        criteria.setTenthEnabled(Boolean.TRUE.equals(req.tenthEnabled()));
        criteria.setMinTenthPercentage(req.minTenthPercentage());
        criteria.setTwelfthEnabled(Boolean.TRUE.equals(req.twelfthEnabled()));
        criteria.setMinTwelfthPercentage(req.minTwelfthPercentage());
        criteria.setDiplomaEnabled(Boolean.TRUE.equals(req.diplomaEnabled()));
        criteria.setMinDiplomaPercentage(req.minDiplomaPercentage());
        criteria.setTwelfthOrDiplomaMode(req.twelfthOrDiplomaMode() == null
                ? AlternativeGateMode.EITHER_SUFFICIENT : req.twelfthOrDiplomaMode());
        criteria.setActiveBacklogsEnabled(Boolean.TRUE.equals(req.activeBacklogsEnabled()));
        criteria.setMaxActiveBacklogs(req.maxActiveBacklogs());
        criteria.setTotalBacklogsEnabled(Boolean.TRUE.equals(req.totalBacklogsEnabled()));
        criteria.setMaxTotalBacklogs(req.maxTotalBacklogs());
        criteria.setGapYearsEnabled(Boolean.TRUE.equals(req.gapYearsEnabled()));
        criteria.setMaxGapYears(req.maxGapYears());
        criteria.setCocubesEnabled(Boolean.TRUE.equals(req.cocubesEnabled()));
        criteria.setMinCocubesScore(req.minCocubesScore());
        criteria.setMissingDataPolicy(req.missingDataPolicy() == null
                ? MissingDataPolicy.INELIGIBLE : req.missingDataPolicy());
        criteria.setCriteriaNote(req.criteriaNote());
        criteria.setUpdatedBy(actor.userId());

        PostingEligibilityCriteria saved = criteriaRepository.save(criteria);

        replaceSemesterCriteria(saved, req, actor);
        replaceAptitudeCriteria(saved, req, actor);
        replaceCustomCriteria(saved, req, actor);

        // The assembled snapshot is cached and read by every resolution and every
        // apply-time re-check. Leaving a stale copy behind would keep enforcing the
        // gate the TnP just replaced -- the one cache staleness here that is not
        // merely cosmetic.
        resolutionService.evictCriteria(postingId);

        log.info("Set eligibility criteria on posting id={} actorId={}", postingId, actor.userId());
        return toResponse(postingId, saved);
    }

    public EligibilityCriteriaResponse getCriteria(Long postingId) {
        try {
            return doGetCriteria(postingId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load criteria for posting id={}: {}", postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load the eligibility criteria.");
        }
    }

    private EligibilityCriteriaResponse doGetCriteria(Long postingId) {
        PostingEligibilityCriteria criteria = criteriaRepository
                .findByPosting_IdAndIsDeletedFalse(postingId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("PostingEligibilityCriteria", postingId));
        return toResponse(postingId, criteria);
    }

    @Transactional
    public ReapplicationPolicyResponse setReapplicationPolicy(UserPrincipal actor, Long postingId,
                                                              ReapplicationPolicyRequest req) {
        try {
            return doSetReapplicationPolicy(actor, postingId, req);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to set re-application policy on posting id={} actorId={}: {}",
                    postingId, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not save the re-application policy. Please try again.");
        }
    }

    private ReapplicationPolicyResponse doSetReapplicationPolicy(UserPrincipal actor, Long postingId,
                                                                  ReapplicationPolicyRequest req) {
        if (!drivePolicy.canDraftPosting(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        JobPosting posting = requireEditablePosting(postingId);

        // An uplift stance with no uplift figure admits every placed student,
        // which is the opposite of what the stance says.
        if (req.placedStudentStance() == ReapplicationStance.ALLOWED_WITH_UPLIFT
                && req.minPackageUpliftPercent() == null && req.minAbsolutePackage() == null) {
            throw new AdminExceptions.InvalidRequestException(
                    "placedStudentStance is ALLOWED_WITH_UPLIFT but neither minPackageUpliftPercent "
                            + "nor minAbsolutePackage was provided.");
        }
        if (req.interningStudentStance() == ReapplicationStance.ALLOWED_WITH_UPLIFT
                && req.minStipendUpliftPercent() == null) {
            throw new AdminExceptions.InvalidRequestException(
                    "interningStudentStance is ALLOWED_WITH_UPLIFT but minStipendUpliftPercent was not provided.");
        }

        PostingReapplicationPolicy policy = policyRepository
                .findByPosting_IdAndIsDeletedFalse(postingId)
                .orElseGet(() -> {
                    PostingReapplicationPolicy fresh = new PostingReapplicationPolicy();
                    fresh.setPosting(posting);
                    fresh.setCreatedBy(actor.userId());
                    return fresh;
                });

        policy.setPlacedStudentStance(req.placedStudentStance());
        policy.setMinPackageUpliftPercent(req.minPackageUpliftPercent());
        policy.setMinAbsolutePackage(req.minAbsolutePackage());
        policy.setInterningStudentStance(req.interningStudentStance());
        policy.setMinStipendUpliftPercent(req.minStipendUpliftPercent());
        policy.setPendingOfferStance(req.pendingOfferStance() == null
                ? ReapplicationStance.ALLOWED : req.pendingOfferStance());
        policy.setMaxLiveOffers(req.maxLiveOffers());
        if (req.countInternshipTowardsPlacement() != null) {
            policy.setCountInternshipTowardsPlacement(req.countInternshipTowardsPlacement());
        }
        if (req.allowIfLastOfferDeclined() != null) {
            policy.setAllowIfLastOfferDeclined(req.allowIfLastOfferDeclined());
        }
        policy.setMinDaysSinceLastOffer(req.minDaysSinceLastOffer());
        if (req.requireAdminDecisionForPlaced() != null) {
            policy.setRequireAdminDecisionForPlaced(req.requireAdminDecisionForPlaced());
        }
        policy.setUpdatedBy(actor.userId());

        PostingReapplicationPolicy saved = policyRepository.save(policy);
        log.info("Set re-application policy on posting id={} placed={} interning={} actorId={}",
                postingId, saved.getPlacedStudentStance(), saved.getInterningStudentStance(), actor.userId());
        return toResponse(postingId, saved);
    }

    public ReapplicationPolicyResponse getReapplicationPolicy(Long postingId) {
        try {
            return doGetReapplicationPolicy(postingId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load re-application policy for posting id={}: {}", postingId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load the re-application policy.");
        }
    }

    private ReapplicationPolicyResponse doGetReapplicationPolicy(Long postingId) {
        PostingReapplicationPolicy policy = policyRepository
                .findByPosting_IdAndIsDeletedFalse(postingId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("PostingReapplicationPolicy", postingId));
        return toResponse(postingId, policy);
    }

    // --- validation ---

    /**
     * An enabled gate with no threshold is the failure mode this whole
     * flag-plus-value design exists to prevent: it looks like a criterion in the
     * UI and admits everyone in practice. Rejected at write time rather than
     * discovered when the wrong cohort gets notified.
     */
    private void validate(EligibilityCriteriaRequest r) {
        requireThreshold(on(r.aggregateCgpaEnabled()), r.minAggregateCgpa(), "aggregateCgpa");
        requireThreshold(on(r.tenthEnabled()), r.minTenthPercentage(), "tenthPercentage");
        requireThreshold(on(r.twelfthEnabled()), r.minTwelfthPercentage(), "twelfthPercentage");
        requireThreshold(on(r.diplomaEnabled()), r.minDiplomaPercentage(), "diplomaPercentage");
        requireThreshold(on(r.activeBacklogsEnabled()), r.maxActiveBacklogs(), "activeBacklogs");
        requireThreshold(on(r.totalBacklogsEnabled()), r.maxTotalBacklogs(), "totalBacklogs");
        requireThreshold(on(r.gapYearsEnabled()), r.maxGapYears(), "gapYears");
        requireThreshold(on(r.cocubesEnabled()), r.minCocubesScore(), "cocubesScore");

        if (on(r.semesterCgpaEnabled()) && (r.semesterCriteria() == null || r.semesterCriteria().isEmpty())) {
            throw new AdminExceptions.CriteriaIncompleteException("semesterCgpa");
        }
        if (on(r.activeBacklogsEnabled()) && on(r.totalBacklogsEnabled())
                && r.maxActiveBacklogs() != null && r.maxTotalBacklogs() != null
                && r.maxActiveBacklogs() > r.maxTotalBacklogs()) {
            throw new AdminExceptions.InvalidRequestException(
                    "maxActiveBacklogs cannot exceed maxTotalBacklogs -- active backlogs are a subset of the total.");
        }
    }

    /** An omitted flag means the gate is off, not a deserialization failure. */
    private boolean on(Boolean flag) {
        return Boolean.TRUE.equals(flag);
    }

    private void requireThreshold(boolean enabled, Object value, String name) {
        if (enabled && value == null) {
            throw new AdminExceptions.CriteriaIncompleteException(name);
        }
    }

    private JobPosting requireEditablePosting(Long postingId) {
        JobPosting posting = postingRepository.findByIdAndIsDeletedFalse(postingId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", postingId));
        if (!PostingStatusTransitions.isEditable(posting.getStatus())) {
            throw new AdminExceptions.PostingNotEditableException(posting.getStatus().name());
        }
        return posting;
    }

    // --- child criteria ---

    private void replaceSemesterCriteria(PostingEligibilityCriteria criteria,
                                         EligibilityCriteriaRequest req, UserPrincipal actor) {
        semesterRepository.deleteByCriteria_Id(criteria.getId());
        semesterRepository.flush();
        if (req.semesterCriteria() == null) {
            return;
        }
        for (EligibilityCriteriaRequest.SemesterCriterionRequest s : req.semesterCriteria()) {
            PostingSemesterCgpaCriterion row = new PostingSemesterCgpaCriterion();
            row.setCriteria(criteria);
            row.setSemester(s.semester());
            row.setMinGpa(s.minGpa());
            row.setCreatedBy(actor.userId());
            row.setUpdatedBy(actor.userId());
            semesterRepository.save(row);
        }
    }

    private void replaceAptitudeCriteria(PostingEligibilityCriteria criteria,
                                         EligibilityCriteriaRequest req, UserPrincipal actor) {
        aptitudeRepository.deleteByCriteria_Id(criteria.getId());
        aptitudeRepository.flush();
        if (req.aptitudeCriteria() == null) {
            return;
        }
        for (EligibilityCriteriaRequest.AptitudeCriterionRequest a : req.aptitudeCriteria()) {
            PostingAptitudeCriterion row = new PostingAptitudeCriterion();
            row.setCriteria(criteria);
            row.setExam(a.exam());
            row.setMinScore(a.minScore());
            row.setCreatedBy(actor.userId());
            row.setUpdatedBy(actor.userId());
            aptitudeRepository.save(row);
        }
    }

    private void replaceCustomCriteria(PostingEligibilityCriteria criteria,
                                       EligibilityCriteriaRequest req, UserPrincipal actor) {
        customRepository.deleteByCriteria_Id(criteria.getId());
        customRepository.flush();
        if (req.customCriteria() == null) {
            return;
        }
        for (EligibilityCriteriaRequest.CustomCriterionRequest c : req.customCriteria()) {
            // A DYNAMIC_FIELD criterion pointing at a field that does not exist
            // can never be evaluated, so it would quietly do nothing. Reject it
            // here rather than let it look like an active gate.
            if (c.source() == CriterionSource.DYNAMIC_FIELD
                    && dynamicFieldDefinitionRepository.findByFieldKey(c.criterionKey()).isEmpty()) {
                throw new AdminExceptions.InvalidRequestException(
                        "No dynamic field is defined with key '" + c.criterionKey()
                                + "'. Create the field first, or use a different criterion source.");
            }
            PostingCustomCriterion row = new PostingCustomCriterion();
            row.setCriteria(criteria);
            row.setCriterionKey(c.criterionKey().trim());
            row.setLabel(c.label());
            row.setSource(c.source());
            row.setOperator(c.operator());
            row.setValueType(c.valueType());
            row.setValueJson(c.valueJson());
            row.setMandatory(!Boolean.FALSE.equals(c.mandatory()));
            row.setMissingDataPolicy(c.missingDataPolicy() == null
                    ? MissingDataPolicy.NEEDS_REVIEW : c.missingDataPolicy());
            row.setCreatedBy(actor.userId());
            row.setUpdatedBy(actor.userId());
            customRepository.save(row);
        }
    }

    // --- mapping ---

    private EligibilityCriteriaResponse toResponse(Long postingId, PostingEligibilityCriteria c) {
        List<EligibilityCriteriaResponse.SemesterCriterionResponse> semesters =
                semesterRepository.findByCriteria_IdAndIsDeletedFalse(c.getId()).stream()
                        .map(s -> new EligibilityCriteriaResponse.SemesterCriterionResponse(
                                s.getId(), s.getSemester(), s.getMinGpa(), s.isEnabled()))
                        .toList();
        List<EligibilityCriteriaResponse.AptitudeCriterionResponse> aptitudes =
                aptitudeRepository.findByCriteria_IdAndIsDeletedFalse(c.getId()).stream()
                        .map(a -> new EligibilityCriteriaResponse.AptitudeCriterionResponse(
                                a.getId(), a.getExam(), a.getMinScore(), a.isEnabled()))
                        .toList();
        List<EligibilityCriteriaResponse.CustomCriterionResponse> customs =
                customRepository.findByCriteria_IdAndIsDeletedFalse(c.getId()).stream()
                        .map(x -> new EligibilityCriteriaResponse.CustomCriterionResponse(
                                x.getId(), x.getCriterionKey(), x.getLabel(), x.getSource(),
                                x.getOperator(), x.getValueType(), x.getValueJson(),
                                x.isMandatory(), x.isEnabled(), x.getMissingDataPolicy()))
                        .toList();

        return new EligibilityCriteriaResponse(
                postingId,
                c.isAggregateCgpaEnabled(), c.getMinAggregateCgpa(),
                c.isSemesterCgpaEnabled(), semesters,
                c.isTenthEnabled(), c.getMinTenthPercentage(),
                c.isTwelfthEnabled(), c.getMinTwelfthPercentage(),
                c.isDiplomaEnabled(), c.getMinDiplomaPercentage(),
                c.getTwelfthOrDiplomaMode(),
                c.isActiveBacklogsEnabled(), c.getMaxActiveBacklogs(),
                c.isTotalBacklogsEnabled(), c.getMaxTotalBacklogs(),
                c.isGapYearsEnabled(), c.getMaxGapYears(),
                c.isCocubesEnabled(), c.getMinCocubesScore(),
                aptitudes, customs,
                c.getMissingDataPolicy(), c.getCriteriaNote());
    }

    private ReapplicationPolicyResponse toResponse(Long postingId, PostingReapplicationPolicy p) {
        return new ReapplicationPolicyResponse(
                postingId,
                p.getPlacedStudentStance(), p.getMinPackageUpliftPercent(), p.getMinAbsolutePackage(),
                p.getInterningStudentStance(), p.getMinStipendUpliftPercent(),
                p.getPendingOfferStance(), p.getMaxLiveOffers(),
                p.isCountInternshipTowardsPlacement(), p.isAllowIfLastOfferDeclined(),
                p.getMinDaysSinceLastOffer(), p.isRequireAdminDecisionForPlaced());
    }
}

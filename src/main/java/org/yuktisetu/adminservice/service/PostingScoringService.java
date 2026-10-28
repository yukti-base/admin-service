package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.dto.ScoringConfigRequest;
import org.yuktisetu.adminservice.dto.ScoringConfigResponse;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.policy.PlacementDrivePolicy;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.JobPosting;
import org.yuktisetu.drive.db.PostingScoringConfig;
import org.yuktisetu.drive.model.ScoringFactor;
import org.yuktisetu.drive.model.ShortlistingMode;
import org.yuktisetu.drive.model.WeightProfile;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PostingScoringConfigRepository;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A drive's composite-scoring configuration: weights, threshold, floors.
 *
 * Editable while the drive is (DRAFT through PUBLISHED); once the pool is sealed
 * it is frozen, because the engine is about to read it, and the run keeps its
 * own snapshot regardless.
 *
 * Weights are whole numbers from 0 to 5 (decided 2026-10-27, replacing the
 * earlier "no limits" rule): the TnP sets them with sliders, and a small fixed
 * scale keeps one drive's weights comparable with another's. At least one must
 * be above zero; they need no particular total, since they become shares. The
 * one extra rule is temporary: factors the engine cannot measure yet stay at 0.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostingScoringService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final int MAX_WEIGHT = 5;

    private final JobPostingRepository postingRepository;
    private final PostingScoringConfigRepository configRepository;
    private final PlacementDrivePolicy drivePolicy;

    @Transactional
    public ScoringConfigResponse set(UserPrincipal actor, Long postingId, ScoringConfigRequest req) {
        if (!drivePolicy.canDraftPosting(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        JobPosting posting = postingRepository.findByIdAndIsDeletedFalse(postingId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("JobPosting", postingId));
        if (!PostingEditing.isEditable(posting.getStatus())) {
            throw new AdminExceptions.PostingNotEditableException(posting.getStatus().name());
        }
        if (posting.getShortlistingMode() != ShortlistingMode.COMPOSITE_SCORE) {
            throw new AdminExceptions.InvalidRequestException(
                    "Scoring weights apply only to drives shortlisted by composite score.");
        }

        Map<ScoringFactor, BigDecimal> weights = new EnumMap<>(ScoringFactor.class);
        for (ScoringFactor f : ScoringFactor.values()) {
            BigDecimal w = req.weights().get(f);
            weights.put(f, w == null ? BigDecimal.ZERO : w);
        }
        validate(weights, req.threshold(), req.floors());

        PostingScoringConfig config = configRepository.findByPostingId(postingId).orElseGet(() -> {
            PostingScoringConfig fresh = new PostingScoringConfig();
            fresh.setPostingId(postingId);
            fresh.setCreatedBy(actor.userId());
            return fresh;
        });
        weights.forEach(config::setWeight);
        config.setThreshold(req.threshold() == null ? BigDecimal.ZERO : req.threshold());
        for (ScoringFactor f : ScoringFactor.values()) {
            config.setFloor(f, req.floors() == null ? null : req.floors().get(f));
        }
        config.setUpdatedBy(actor.userId());
        PostingScoringConfig saved = configRepository.save(config);

        log.info("Set scoring config on posting id={} actorId={} weights={} threshold={} floors={}",
                postingId, actor.userId(), weights, saved.getThreshold(), saved.floors());
        return toResponse(postingId, saved);
    }

    @Transactional(readOnly = true)
    public ScoringConfigResponse get(Long postingId) {
        return configRepository.findByPostingId(postingId)
                .map(c -> toResponse(postingId, c))
                .orElseGet(() -> new ScoringConfigResponse(postingId, false, Map.of(), Map.of(),
                        BigDecimal.ZERO, Map.of(), scorable()));
    }

    /** True when the drive has weights the engine can run on. Used by publish. */
    public boolean isConfigured(Long postingId) {
        return configRepository.findByPostingId(postingId)
                .map(c -> c.weights().values().stream().anyMatch(w -> w != null && w.signum() > 0))
                .orElse(false);
    }

    void validate(Map<ScoringFactor, BigDecimal> weights, BigDecimal threshold,
                          Map<ScoringFactor, BigDecimal> floors) {
        for (Map.Entry<ScoringFactor, BigDecimal> e : weights.entrySet()) {
            if (!e.getKey().isScorable() && e.getValue().signum() != 0) {
                throw new AdminExceptions.InvalidRequestException(
                        label(e.getKey()) + " cannot be weighted yet; set it to 0.");
            }
        }
        for (Map.Entry<ScoringFactor, BigDecimal> e : weights.entrySet()) {
            BigDecimal w = e.getValue();
            if (w.signum() < 0 || w.compareTo(BigDecimal.valueOf(MAX_WEIGHT)) > 0
                    || w.stripTrailingZeros().scale() > 0) {
                throw new AdminExceptions.InvalidRequestException(
                        "The weight for " + label(e.getKey()) + " must be a whole number from 0 to "
                                + MAX_WEIGHT + ".");
            }
        }
        Map<ScoringFactor, BigDecimal> positive = new EnumMap<>(ScoringFactor.class);
        weights.forEach((f, w) -> { if (w.signum() > 0) positive.put(f, w); });
        try {
            // The negative check lives in WeightProfile; a negative weight is
            // never in "positive", so check it here too.
            weights.forEach((f, w) -> {
                if (w.signum() < 0) {
                    throw new IllegalArgumentException(label(f) + " has a negative weight.");
                }
            });
            WeightProfile.of(positive);
        } catch (IllegalArgumentException e) {
            throw new AdminExceptions.InvalidRequestException(
                    positive.isEmpty() ? "Weight at least one factor above 0." : e.getMessage());
        }
        if (threshold != null && (threshold.signum() < 0 || threshold.compareTo(HUNDRED) > 0)) {
            throw new AdminExceptions.InvalidRequestException("The threshold must be between 0 and 100.");
        }
        if (floors != null) {
            for (Map.Entry<ScoringFactor, BigDecimal> e : floors.entrySet()) {
                BigDecimal v = e.getValue();
                if (v == null) {
                    continue;
                }
                if (v.signum() < 0 || v.compareTo(HUNDRED) > 0) {
                    throw new AdminExceptions.InvalidRequestException(
                            "The floor for " + label(e.getKey()) + " must be a percentile between 0 and 100.");
                }
                if (weights.get(e.getKey()).signum() == 0) {
                    throw new AdminExceptions.InvalidRequestException(
                            "A floor on " + label(e.getKey()) + " needs that factor to be weighted above 0.");
                }
            }
        }
    }

    private ScoringConfigResponse toResponse(Long postingId, PostingScoringConfig c) {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        c.weights().forEach((f, w) -> weights.put(f.name(), w));
        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        boolean any = c.weights().values().stream().anyMatch(w -> w.signum() > 0);
        if (any) {
            WeightProfile p = c.toWeightProfile();
            p.sharesOver(p.weightedFactors()).forEach((f, s) -> shares.put(f.name(), s));
        }
        Map<String, BigDecimal> floors = new LinkedHashMap<>();
        c.floors().forEach((f, v) -> floors.put(f.name(), v));
        return new ScoringConfigResponse(postingId, true, weights, shares, c.getThreshold(), floors, scorable());
    }

    private static List<String> scorable() {
        return Arrays.stream(ScoringFactor.values()).filter(ScoringFactor::isScorable).map(Enum::name).toList();
    }

    private static String label(ScoringFactor f) {
        return switch (f) {
            case ACADEMIC_PERFORMANCE -> "Academic performance";
            case CODING_PROFICIENCY -> "Coding proficiency";
            case SKILL_MATCH -> "Skill match";
            case EXPERIENCE_RELEVANCE -> "Experience relevance";
            case CERTIFICATIONS -> "Certifications";
        };
    }
}

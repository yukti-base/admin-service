package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.NotNull;
import org.yuktisetu.drive.model.ScoringFactor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A drive's scoring weights, threshold and floors, replaced wholesale.
 *
 * weights: any non-negative numbers, normalised to shares of 100 by the engine,
 * so 2/1 and 60/30 mean the same thing. No defaults and no limits by design.
 * floors: optional per-factor minimum percentile, 0-100.
 */
public record ScoringConfigRequest(
        @NotNull Map<ScoringFactor, BigDecimal> weights,
        BigDecimal threshold,
        Map<ScoringFactor, BigDecimal> floors
) {}

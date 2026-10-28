package org.yuktisetu.adminservice.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * shares are the weights as percentages of their total, as the engine applies
 * them. scorableFactors are the ones that may be weighted above zero today.
 * configured is false when nothing has been saved yet.
 */
public record ScoringConfigResponse(
        Long postingId,
        boolean configured,
        Map<String, BigDecimal> weights,
        Map<String, BigDecimal> shares,
        BigDecimal threshold,
        Map<String, BigDecimal> floors,
        List<String> scorableFactors
) {}

package org.yuktisetu.adminservice.dto;

import org.yuktisetu.drive.model.EligibilityRunReason;
import org.yuktisetu.drive.model.EligibilityRunStatus;

import java.time.Instant;
import java.util.Map;

/**
 * The TnP's answer to "why did only 40 of 300 students qualify". failureSummary
 * is a count per failure code, which gives that answer without storing a row per
 * rejected student.
 */
public record EligibilityRunResponse(
        Long runId,
        Long postingId,
        EligibilityRunReason reason,
        EligibilityRunStatus status,
        Instant startedAt,
        Instant finishedAt,
        Integer candidatesEvaluated,
        Integer eligibleCount,
        Integer ineligibleCount,
        Map<String, Integer> failureSummary,
        String error
) {}

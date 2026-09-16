package org.yuktisetu.adminservice.dto;

import org.yuktisetu.model.AlternativeGateMode;
import org.yuktisetu.model.AptitudeExam;
import org.yuktisetu.model.CriterionOperator;
import org.yuktisetu.model.CriterionSource;
import org.yuktisetu.model.CriterionValueType;
import org.yuktisetu.model.MissingDataPolicy;

import java.math.BigDecimal;
import java.util.List;

public record EligibilityCriteriaResponse(
        Long postingId,
        boolean aggregateCgpaEnabled, BigDecimal minAggregateCgpa,
        boolean semesterCgpaEnabled, List<SemesterCriterionResponse> semesterCriteria,
        boolean tenthEnabled, BigDecimal minTenthPercentage,
        boolean twelfthEnabled, BigDecimal minTwelfthPercentage,
        boolean diplomaEnabled, BigDecimal minDiplomaPercentage,
        AlternativeGateMode twelfthOrDiplomaMode,
        boolean activeBacklogsEnabled, Integer maxActiveBacklogs,
        boolean totalBacklogsEnabled, Integer maxTotalBacklogs,
        boolean gapYearsEnabled, Integer maxGapYears,
        boolean cocubesEnabled, BigDecimal minCocubesScore,
        List<AptitudeCriterionResponse> aptitudeCriteria,
        List<CustomCriterionResponse> customCriteria,
        MissingDataPolicy missingDataPolicy,
        String criteriaNote
) {
    public record SemesterCriterionResponse(Long id, Short semester, BigDecimal minGpa, boolean enabled) {}

    public record AptitudeCriterionResponse(Long id, AptitudeExam exam, BigDecimal minScore, boolean enabled) {}

    public record CustomCriterionResponse(
            Long id, String criterionKey, String label,
            CriterionSource source, CriterionOperator operator, CriterionValueType valueType,
            String valueJson, boolean mandatory, boolean enabled, MissingDataPolicy missingDataPolicy
    ) {}
}

package org.yuktisetu.adminservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.yuktisetu.model.AlternativeGateMode;
import org.yuktisetu.model.AptitudeExam;
import org.yuktisetu.model.CriterionOperator;
import org.yuktisetu.model.CriterionSource;
import org.yuktisetu.model.CriterionValueType;
import org.yuktisetu.model.MissingDataPolicy;

import java.math.BigDecimal;
import java.util.List;

/**
 * The hard eligibility gate. Every criterion is a flag plus a threshold, and the
 * service rejects any flag set true with a null threshold -- an enabled gate
 * with no value would silently admit everyone, which is worse than no gate at
 * all because it looks like one.
 *
 * The whole set is replaced in one call; criteria are edited as a unit.
 */
public record EligibilityCriteriaRequest(
        // Every flag is a Boolean rather than a primitive: a request that simply
        // omits a gate must mean "that gate is off", not fail deserialization with
        // a 500. Boot 4's Jackson refuses to map a missing value onto a primitive.
        Boolean aggregateCgpaEnabled,
        @Min(0) @Max(10) BigDecimal minAggregateCgpa,

        Boolean semesterCgpaEnabled,
        @Valid List<SemesterCriterionRequest> semesterCriteria,

        Boolean tenthEnabled,
        @Min(0) @Max(100) BigDecimal minTenthPercentage,

        Boolean twelfthEnabled,
        @Min(0) @Max(100) BigDecimal minTwelfthPercentage,

        Boolean diplomaEnabled,
        @Min(0) @Max(100) BigDecimal minDiplomaPercentage,

        /** EITHER_SUFFICIENT keeps diploma-lateral students, who have no 12th marks, in the pool. */
        AlternativeGateMode twelfthOrDiplomaMode,

        Boolean activeBacklogsEnabled,
        @Min(0) Integer maxActiveBacklogs,

        Boolean totalBacklogsEnabled,
        @Min(0) Integer maxTotalBacklogs,

        Boolean gapYearsEnabled,
        @Min(0) Integer maxGapYears,

        Boolean cocubesEnabled,
        @Min(0) BigDecimal minCocubesScore,

        @Valid List<AptitudeCriterionRequest> aptitudeCriteria,
        @Valid List<CustomCriterionRequest> customCriteria,

        MissingDataPolicy missingDataPolicy,
        String criteriaNote
) {
    public record SemesterCriterionRequest(
            @NotNull @Min(1) @Max(8) Short semester,
            @NotNull @Min(0) @Max(10) BigDecimal minGpa
    ) {}

    public record AptitudeCriterionRequest(
            @NotNull AptitudeExam exam,
            @NotNull @Min(0) BigDecimal minScore
    ) {}

    /**
     * A company one-off, added without a deployment. For source DYNAMIC_FIELD the
     * criterionKey must match an existing DynamicFieldDefinition.fieldKey, so
     * these plug into the Advanced Fields mechanism rather than a parallel store.
     */
    public record CustomCriterionRequest(
            @NotBlank @Size(max = 64) String criterionKey,
            @NotBlank @Size(max = 128) String label,
            @NotNull CriterionSource source,
            @NotNull CriterionOperator operator,
            @NotNull CriterionValueType valueType,
            @NotBlank String valueJson,
            Boolean mandatory,
            MissingDataPolicy missingDataPolicy
    ) {}
}

package org.yuktisetu.adminservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.yuktisetu.drive.model.JobType;
import org.yuktisetu.drive.model.PackageBasis;
import org.yuktisetu.drive.model.ShortlistingMode;
import org.yuktisetu.drive.model.StipendPeriod;
import org.yuktisetu.drive.model.WorkMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The JD as the TnP enters it, after reading the company's document.
 *
 * applicationDeadline is required at draft time rather than at publish: a
 * posting with no deadline is a posting whose applications never close, and
 * discovering that at publish is a worse moment to find out.
 */
public record JobPostingRequest(
        @NotNull Long companyId,
        @NotBlank @Size(max = 32) String postingCode,
        @NotBlank @Size(max = 255) String title,
        @NotNull JobType jobType,
        WorkMode workMode,
        String description,
        String responsibilities,

        // Compensation. Numerics, never free text -- the re-application uplift
        // rule has to be able to compare these.
        BigDecimal ctcMin,
        BigDecimal ctcMax,
        @Size(max = 3) String ctcCurrency,
        PackageBasis packageBasis,
        BigDecimal stipendAmount,
        StipendPeriod stipendPeriod,
        @Positive Integer internshipDurationMonths,
        Boolean ppoPossible,
        Integer bondMonths,
        BigDecimal bondAmount,
        @Positive Integer vacancyCount,

        @NotBlank @Size(max = 9) String academicYear,
        Instant applicationOpensAt,
        @NotNull Instant applicationDeadline,
        Instant driveDate,
        @Size(max = 255) String driveVenue,

        ShortlistingMode shortlistingMode,
        Boolean marksStudentPlaced,
        @Positive Integer maxApplications,

        Boolean notifyOnPublish,

        @Valid List<SkillRequest> skills,
        @Valid List<LocationRequest> locations
) {
    public record SkillRequest(@NotBlank @Size(max = 64) String skill, Boolean mandatory) {}

    public record LocationRequest(
            @NotBlank @Size(max = 96) String city,
            @Size(max = 96) String state,
            @Size(max = 96) String country
    ) {}
}

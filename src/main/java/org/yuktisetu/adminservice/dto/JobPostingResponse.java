package org.yuktisetu.adminservice.dto;

import org.yuktisetu.model.JobType;
import org.yuktisetu.model.PackageBasis;
import org.yuktisetu.model.PostingStatus;
import org.yuktisetu.model.ShortlistingMode;
import org.yuktisetu.model.StipendPeriod;
import org.yuktisetu.model.WorkMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record JobPostingResponse(
        Long id,
        Long companyId,
        String companyName,
        String postingCode,
        String title,
        JobType jobType,
        WorkMode workMode,
        String description,
        String responsibilities,
        BigDecimal ctcMin,
        BigDecimal ctcMax,
        String ctcCurrency,
        PackageBasis packageBasis,
        BigDecimal stipendAmount,
        StipendPeriod stipendPeriod,
        Integer internshipDurationMonths,
        boolean ppoPossible,
        Integer bondMonths,
        BigDecimal bondAmount,
        Integer vacancyCount,
        String academicYear,
        PostingStatus status,
        Instant applicationOpensAt,
        Instant applicationDeadline,
        Instant applicationsClosedAt,
        Instant publishedAt,
        Long publishedBy,
        Instant driveDate,
        String driveVenue,
        ShortlistingMode shortlistingMode,
        boolean marksStudentPlaced,
        Integer maxApplications,
        boolean notifyOnPublish,
        List<SkillResponse> skills,
        List<LocationResponse> locations,
        List<TargetResponse> targets,
        boolean editable,
        Instant createdAt,
        Instant updatedAt
) {
    public record SkillResponse(Long id, String skill, boolean mandatory) {}

    public record LocationResponse(Long id, String city, String state, String country) {}

    public record TargetResponse(Long id, Long collegeId, Long departmentId, Integer batchYear, String degree) {}
}

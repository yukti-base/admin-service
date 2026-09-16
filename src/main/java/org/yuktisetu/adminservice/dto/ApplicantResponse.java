package org.yuktisetu.adminservice.dto;

import org.yuktisetu.model.ApplicationSource;
import org.yuktisetu.model.ApplicationStatus;

import java.time.Instant;

/**
 * One row of the TnP's applicant pool. Carries the academic figures the
 * coordinator actually works from, read live off the profile rather than
 * snapshotted onto the application.
 */
public record ApplicantResponse(
        Long applicationId,
        Long studentUserId,
        String studentName,
        String email,
        String phone,
        Long collegeId,
        Long departmentId,
        Integer batchYear,
        Double cgpa,
        Integer activeBacklogs,
        ApplicationStatus status,
        ApplicationSource source,
        Instant appliedAt,
        String disqualificationReason
) {}

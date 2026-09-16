package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.yuktisetu.model.CompanyStatus;

/**
 * reason is required when moving to BLACKLISTED. Cutting a recruiter off across
 * the whole trust with no recorded rationale is how the decision becomes
 * unexplainable to the next TnP team.
 */
public record CompanyStatusChangeRequest(
        @NotNull CompanyStatus status,
        @Size(max = 512) String reason
) {}

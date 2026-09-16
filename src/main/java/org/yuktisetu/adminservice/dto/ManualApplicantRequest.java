package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The TnP adding applicants by hand -- single or bulk in one shape, since both
 * paths need the identical audit trail and there is no reason for two endpoints.
 *
 * This BYPASSES the eligibility gate by design: it is how the TnP includes a
 * student the system filtered out, or records a candidate the company shortlisted
 * outside the portal. reason is mandatory precisely because the gate was skipped,
 * and every row created this way is stamped ApplicationSource.TNP_ADDED so it
 * stays distinguishable from a student's own application in every count and export.
 */
public record ManualApplicantRequest(
        @NotEmpty List<@NotNull Long> studentUserIds,
        @NotNull @Size(min = 3, max = 512) String reason
) {}

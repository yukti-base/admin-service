package org.yuktisetu.adminservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Who the drive is open to. Replaces the whole target set in one call rather
 * than patching rows, so the TnP never ends up with a half-edited audience.
 *
 * departmentId null means every department in that college; degree null means
 * any degree. NotEmpty because a posting with no targets has no audience and
 * cannot be published.
 */
public record PostingTargetsRequest(
        @NotEmpty @Valid List<TargetRequest> targets
) {
    public record TargetRequest(
            @NotNull Long collegeId,
            Long departmentId,
            @NotNull Integer batchYear,
            @Size(max = 32) String degree
    ) {}
}

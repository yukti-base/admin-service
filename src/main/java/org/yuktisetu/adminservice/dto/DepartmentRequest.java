package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DepartmentRequest(
        @NotNull Long collegeId,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 32) String code
) {}

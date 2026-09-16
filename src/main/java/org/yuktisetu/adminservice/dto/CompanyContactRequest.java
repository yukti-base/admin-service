package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyContactRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 128) String designation,
        @NotBlank @Email @Size(max = 320) String email,
        @Size(max = 24) String phone,
        Boolean isPrimary,
        @Size(max = 512) String notes
) {}

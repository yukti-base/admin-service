package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TrustRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 32) String code,
        String logoUrl,
        @Email String primaryContactEmail,
        String primaryContactPhone
) {}

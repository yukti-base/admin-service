package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.yuktisetu.model.CompanyTier;
import org.yuktisetu.model.CompanyType;

public record CompanyRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String legalName,
        @NotBlank @Size(max = 32) String code,
        CompanyType companyType,
        CompanyTier tier,
        @Size(max = 255) String website,
        @Size(max = 64) String industry,
        @Size(max = 512) String logoUrl,
        String description,
        @Size(max = 96) String hqCity,
        @Size(max = 96) String hqState,
        @Size(max = 96) String hqCountry,
        @Size(max = 64) String registrationNumber,
        // Optional primary contact, created alongside the company so onboarding
        // is one call rather than two.
        @Size(max = 255) String primaryContactName,
        @Size(max = 128) String primaryContactDesignation,
        @Email @Size(max = 320) String primaryContactEmail,
        @Size(max = 24) String primaryContactPhone
) {}

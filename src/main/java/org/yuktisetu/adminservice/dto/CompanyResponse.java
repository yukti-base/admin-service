package org.yuktisetu.adminservice.dto;

import org.yuktisetu.model.CompanyStatus;
import org.yuktisetu.model.CompanyTier;
import org.yuktisetu.model.CompanyType;

import java.time.Instant;

public record CompanyResponse(
        Long id,
        String name,
        String legalName,
        String code,
        CompanyType companyType,
        CompanyTier tier,
        CompanyStatus status,
        String website,
        String industry,
        String logoUrl,
        String description,
        String hqCity,
        String hqState,
        String hqCountry,
        String registrationNumber,
        String blacklistReason,
        Instant blacklistedAt,
        Instant createdAt,
        Instant updatedAt
) {}

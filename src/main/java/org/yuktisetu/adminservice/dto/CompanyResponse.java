package org.yuktisetu.adminservice.dto;

import org.yuktisetu.drive.model.CompanyStatus;
import org.yuktisetu.drive.model.CompanyTier;
import org.yuktisetu.drive.model.CompanyType;

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

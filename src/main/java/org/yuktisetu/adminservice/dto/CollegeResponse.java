package org.yuktisetu.adminservice.dto;

import org.yuktisetu.model.TenantStatus;

import java.time.Instant;

public record CollegeResponse(
        Long id,
        Long trustId,
        String name,
        String code,
        String logoUrl,
        String address,
        String primaryContactName,
        String primaryContactEmail,
        String primaryContactPhone,
        TenantStatus status,
        Instant createdAt,
        Instant updatedAt
) {}

package org.yuktisetu.adminservice.dto;

import org.yuktisetu.model.TenantStatus;

import java.time.Instant;

public record TrustResponse(
        Long id,
        String name,
        String code,
        String logoUrl,
        String primaryContactEmail,
        String primaryContactPhone,
        TenantStatus status,
        Instant createdAt,
        Instant updatedAt
) {}

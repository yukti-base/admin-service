package org.yuktisetu.adminservice.dto;

import org.yuktisetu.model.TenantStatus;

import java.time.Instant;

public record DepartmentResponse(
        Long id,
        Long collegeId,
        String name,
        String code,
        TenantStatus status,
        Instant createdAt,
        Instant updatedAt
) {}

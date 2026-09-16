package org.yuktisetu.adminservice.dto;

import java.time.Instant;

public record CompanyContactResponse(
        Long id,
        Long companyId,
        String name,
        String designation,
        String email,
        String phone,
        boolean isPrimary,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {}

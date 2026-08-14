package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.NotNull;
import org.yuktisetu.model.TenantStatus;

public record StatusChangeRequest(@NotNull TenantStatus status) {}

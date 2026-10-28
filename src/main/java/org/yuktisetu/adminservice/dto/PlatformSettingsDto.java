package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public final class PlatformSettingsDto {

    private PlatformSettingsDto() {}

    public record Response(boolean showScoresToStudents, Long updatedBy, Instant updatedAt) {}

    public record Patch(@NotNull Boolean showScoresToStudents) {}
}

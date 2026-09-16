package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** reason is mandatory: this is a decision a student will ask the TnP to justify. */
public record DisqualifyRequest(@NotNull @Size(min = 3, max = 512) String reason) {}

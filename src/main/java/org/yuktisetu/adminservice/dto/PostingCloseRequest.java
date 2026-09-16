package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.Size;

/** reason is optional for an early close and mandatory for a cancel (checked in the service). */
public record PostingCloseRequest(@Size(max = 512) String reason) {}

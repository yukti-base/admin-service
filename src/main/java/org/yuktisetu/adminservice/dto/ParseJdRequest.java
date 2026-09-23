package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Raw job-description text to read. Capped because this is a paste box, not a
 * file upload -- a 200 KB body here means someone pasted the wrong thing.
 */
public record ParseJdRequest(
        @NotBlank @Size(max = 60_000) String text
) {}

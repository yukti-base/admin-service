package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// This is the "Add College" wizard payload from SSOT Section 8: name, code,
// logo, coordinator contact. trustId is required here rather than inferred,
// because IT_ADMIN/TNP_SUPER_ADMIN are trust-wide today but the SSOT never
// rules out PCET operating more than one Trust in future -- don't bake in
// "there is exactly one Trust" by making this implicit.
public record CollegeRequest(
        @NotNull Long trustId,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 32) String code,
        String logoUrl,
        String address,
        @NotBlank @Size(max = 255) String primaryContactName,
        @NotBlank @Email String primaryContactEmail,
        String primaryContactPhone
) {}

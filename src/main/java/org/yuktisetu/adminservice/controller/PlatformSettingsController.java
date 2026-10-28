package org.yuktisetu.adminservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PatchMapping;
import org.yuktisetu.adminservice.dto.PlatformSettingsDto;
import org.yuktisetu.adminservice.service.PlatformSettingsService;

/**
 * Platform-wide switches. Read by any TnP staff member, so the portal can show
 * the current state; changed only by the TnP Super Admin (and IT Admin).
 */
@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class PlatformSettingsController {

    private final PlatformSettingsService settingsService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')")
    public ResponseEntity<YuktiSetuResponse<PlatformSettingsDto.Response>> get() {
        return ResponseEntity.ok(YuktiSetuResponse.success(settingsService.get()));
    }

    @PatchMapping
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<PlatformSettingsDto.Response>> patch(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody @Valid PlatformSettingsDto.Patch request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(settingsService.patch(actor, request), "Settings updated"));
    }
}

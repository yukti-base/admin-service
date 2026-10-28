package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.dto.PlatformSettingsDto;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.PlatformSettings;
import org.yuktisetu.drive.repository.PlatformSettingsRepository;

import java.time.Instant;

/** The platform-wide switches. One row; every change is logged with its actor. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    private final PlatformSettingsRepository repository;

    @Transactional(readOnly = true)
    public PlatformSettingsDto.Response get() {
        return toResponse(repository.current());
    }

    @Transactional
    public PlatformSettingsDto.Response patch(UserPrincipal actor, PlatformSettingsDto.Patch patch) {
        PlatformSettings s = repository.findById(PlatformSettings.SINGLETON_ID).orElseGet(PlatformSettings::new);
        boolean before = s.isShowScoresToStudents();
        s.setShowScoresToStudents(patch.showScoresToStudents());
        s.setUpdatedBy(actor.userId());
        s.setUpdatedAt(Instant.now());
        repository.save(s);
        log.info("AUDIT platform setting changed: show_scores_to_students {} -> {} actorId={}",
                before, s.isShowScoresToStudents(), actor.userId());
        return toResponse(s);
    }

    private static PlatformSettingsDto.Response toResponse(PlatformSettings s) {
        return new PlatformSettingsDto.Response(s.isShowScoresToStudents(), s.getUpdatedBy(), s.getUpdatedAt());
    }
}

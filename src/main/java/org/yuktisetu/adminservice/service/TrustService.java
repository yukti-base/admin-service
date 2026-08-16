package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.dto.TrustRequest;
import org.yuktisetu.adminservice.dto.TrustResponse;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.policy.InstituteHierarchyPolicy;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.db.Trust;
import org.yuktisetu.model.TenantStatus;
import org.yuktisetu.repository.CollegeRepository;
import org.yuktisetu.repository.TrustRepository;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrustService {

    private final TrustRepository trustRepository;
    private final CollegeRepository collegeRepository;
    private final InstituteHierarchyPolicy policy;

    @Transactional
    public TrustResponse create(UserPrincipal actor, TrustRequest req) {
        if (!policy.canManageTrust(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }

        if (req.name().isBlank() || req.code().isBlank()) {
            throw new AdminExceptions.InvalidRequestException("Trust name and code cannot be blank");
        }

        String code = req.code().trim();
        if (trustRepository.existsByCodeIgnoreCaseAndIsDeletedFalse(code)) {
            throw new AdminExceptions.DuplicateCodeException("Trust", code);
        }

        Trust trust = new Trust();
        trust.setName(req.name().trim());
        trust.setCode(code);
        trust.setLogoUrl(req.logoUrl());
        trust.setPrimaryContactEmail(req.primaryContactEmail());
        trust.setPrimaryContactPhone(req.primaryContactPhone());
        trust.setStatus(TenantStatus.ACTIVE);
        trust.setCreatedBy(actor.userId());
        trust.setUpdatedBy(actor.userId());

        return toResponse(trustRepository.save(trust));
    }

    public List<TrustResponse> listActive() {
        return trustRepository.findByIsDeletedFalse().stream().map(this::toResponse).toList();
    }

    public TrustResponse get(Long id) {
        Trust trust = trustRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Trust", id));
        return toResponse(trust);
    }

    @Transactional
    public TrustResponse update(UserPrincipal actor, Long id, TrustRequest req) {
        if (!policy.canManageTrust(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }

        Trust trust = trustRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Trust", id));

        String code = req.code().trim();
        if (!code.equalsIgnoreCase(trust.getCode())
                && trustRepository.existsByCodeIgnoreCaseAndIsDeletedFalse(code)) {
            throw new AdminExceptions.DuplicateCodeException("Trust", code);
        }

        trust.setName(req.name().trim());
        trust.setCode(code);
        trust.setLogoUrl(req.logoUrl());
        trust.setPrimaryContactEmail(req.primaryContactEmail());
        trust.setPrimaryContactPhone(req.primaryContactPhone());
        trust.setUpdatedBy(actor.userId());

        return toResponse(trustRepository.save(trust));
    }

    // Status toggle -- a reversible pause, NOT the soft-delete/restore flow.
    // Kept as one method with a target status rather than separate
    // activate()/deactivate() to avoid duplicating the same guard twice.
    @Transactional
    public TrustResponse setStatus(UserPrincipal actor, Long id, TenantStatus status) {
        if (!policy.canManageTrust(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Trust trust = trustRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Trust", id));
        trust.setStatus(status);
        trust.setUpdatedBy(actor.userId());
        return toResponse(trustRepository.save(trust));
    }

    @Transactional
    public void softDelete(UserPrincipal actor, Long id) {
        if (!policy.canRestore(actor)) { // same authority tier as restore -- IT_ADMIN/TNP_SUPER_ADMIN
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Trust trust = trustRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Trust", id));
        trust.setDeleted(true);
        trust.setDeletedAt(Instant.now());
        trust.setDeletedBy(actor.userId());
        trustRepository.save(trust);
    }

    @Transactional
    public TrustResponse restore(UserPrincipal actor, Long id) {
        if (!policy.canRestore(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Trust trust = trustRepository.findById(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Trust", id));
        trust.setDeleted(false);
        trust.setDeletedAt(null);
        trust.setDeletedBy(null);
        trust.setUpdatedBy(actor.userId());
        return toResponse(trustRepository.save(trust));
    }

    @Transactional
    public void hardDelete(UserPrincipal actor, Long id) {
        if (!policy.canHardDelete(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Trust trust = trustRepository.findById(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Trust", id));
        if (!trust.isDeleted()) {
            throw new AdminExceptions.NotYetDeactivatedException();
        }
        // Guard: refuse if ANY College row -- soft-deleted or not -- still
        // references this Trust. See CollegeRepository_ADDITIONS.java.
        if (collegeRepository.existsByTrustId(id)) {
            throw new AdminExceptions.HasDependentRecordsException("Trust", id, "College");
        }
        trustRepository.delete(trust);
    }

    private TrustResponse toResponse(Trust t) {
        return new TrustResponse(
                t.getId(), t.getName(), t.getCode(), t.getLogoUrl(),
                t.getPrimaryContactEmail(), t.getPrimaryContactPhone(),
                t.getStatus(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}

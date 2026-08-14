package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.dto.CollegeRequest;
import org.yuktisetu.adminservice.dto.CollegeResponse;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.policy.InstituteHierarchyPolicy;
import org.yuktisetu.adminservice.security.UserPrincipal;
import org.yuktisetu.db.College;
import org.yuktisetu.db.Trust;
import org.yuktisetu.model.TenantStatus;
import org.yuktisetu.repository.CollegeRepository;
import org.yuktisetu.repository.DepartmentRepository;
import org.yuktisetu.repository.TrustRepository;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CollegeService {

    private final CollegeRepository collegeRepository;
    private final TrustRepository trustRepository;
    private final DepartmentRepository departmentRepository;
    private final InstituteHierarchyPolicy policy;

    @Transactional
    public CollegeResponse create(UserPrincipal actor, CollegeRequest req) {
        if (!policy.canManageCollege(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }

        Trust trust = trustRepository.findByIdAndIsDeletedFalse(req.trustId())
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Trust", req.trustId()));
        if (trust.getStatus() != TenantStatus.ACTIVE) {
            throw new AdminExceptions.ParentNotActiveException("Trust", trust.getId());
        }

        String code = req.code().trim();
        if (collegeRepository.existsByTrustIdAndCodeIgnoreCaseAndIsDeletedFalse(trust.getId(), code)) {
            throw new AdminExceptions.DuplicateCodeException("College", code);
        }

        College college = new College();
        college.setTrust(trust);
        college.setName(req.name().trim());
        college.setCode(code);
        college.setLogoUrl(req.logoUrl());
        college.setAddress(req.address());
        college.setPrimaryContactName(req.primaryContactName().trim());
        college.setPrimaryContactEmail(req.primaryContactEmail());
        college.setPrimaryContactPhone(req.primaryContactPhone());
        college.setStatus(TenantStatus.ACTIVE);
        college.setCreatedBy(actor.userId());
        college.setUpdatedBy(actor.userId());

        // NOTE: SSOT Section 8's onboarding walkthrough has this step
        // auto-emailing a welcome message with login details to
        // primaryContactEmail once the college is created. That's the
        // Notification Service's job, not admin-service's -- wire an event
        // publish (Kafka, per the architecture doc) here once that service
        // exists. Deliberately left undone rather than faked with a direct
        // SMTP call from inside this transaction.
        return toResponse(collegeRepository.save(college));
    }

    public List<CollegeResponse> listActiveForTrust(Long trustId) {
        return collegeRepository.findByTrustIdAndIsDeletedFalse(trustId).stream().map(this::toResponse).toList();
    }

    public CollegeResponse get(Long id) {
        College college = collegeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("College", id));
        return toResponse(college);
    }

    @Transactional
    public CollegeResponse update(UserPrincipal actor, Long id, CollegeRequest req) {
        if (!policy.canManageCollege(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }

        College college = collegeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("College", id));

        String code = req.code().trim();
        if (!code.equalsIgnoreCase(college.getCode())
                && collegeRepository.existsByTrustIdAndCodeIgnoreCaseAndIsDeletedFalse(college.getTrust().getId(), code)) {
            throw new AdminExceptions.DuplicateCodeException("College", code);
        }

        college.setName(req.name().trim());
        college.setCode(code);
        college.setLogoUrl(req.logoUrl());
        college.setAddress(req.address());
        college.setPrimaryContactName(req.primaryContactName().trim());
        college.setPrimaryContactEmail(req.primaryContactEmail());
        college.setPrimaryContactPhone(req.primaryContactPhone());
        college.setUpdatedBy(actor.userId());

        return toResponse(collegeRepository.save(college));
    }

    @Transactional
    public CollegeResponse setStatus(UserPrincipal actor, Long id, TenantStatus status) {
        if (!policy.canManageCollege(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        College college = collegeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("College", id));
        college.setStatus(status);
        college.setUpdatedBy(actor.userId());
        return toResponse(collegeRepository.save(college));
    }

    @Transactional
    public void softDelete(UserPrincipal actor, Long id) {
        if (!policy.canRestore(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        College college = collegeRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("College", id));
        college.setDeleted(true);
        college.setDeletedAt(Instant.now());
        college.setDeletedBy(actor.userId());
        collegeRepository.save(college);
    }

    @Transactional
    public CollegeResponse restore(UserPrincipal actor, Long id) {
        if (!policy.canRestore(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        College college = collegeRepository.findById(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("College", id));
        college.setDeleted(false);
        college.setDeletedAt(null);
        college.setDeletedBy(null);
        college.setUpdatedBy(actor.userId());
        return toResponse(collegeRepository.save(college));
    }

    @Transactional
    public void hardDelete(UserPrincipal actor, Long id) {
        if (!policy.canHardDelete(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        College college = collegeRepository.findById(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("College", id));
        if (!college.isDeleted()) {
            throw new AdminExceptions.NotYetDeactivatedException();
        }
        // Guard: refuse if ANY Department row -- soft-deleted or not -- still
        // references this College. See DepartmentRepository_ADDITIONS.java.
        if (departmentRepository.existsByCollegeId(id)) {
            throw new AdminExceptions.HasDependentRecordsException("College", id, "Department");
        }
        collegeRepository.delete(college);
    }

    private CollegeResponse toResponse(College c) {
        return new CollegeResponse(
                c.getId(), c.getTrust().getId(), c.getName(), c.getCode(), c.getLogoUrl(),
                c.getAddress(), c.getPrimaryContactName(), c.getPrimaryContactEmail(),
                c.getPrimaryContactPhone(), c.getStatus(), c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}

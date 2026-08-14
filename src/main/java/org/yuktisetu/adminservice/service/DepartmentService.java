package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.dto.DepartmentRequest;
import org.yuktisetu.adminservice.dto.DepartmentResponse;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.policy.InstituteHierarchyPolicy;
import org.yuktisetu.adminservice.security.UserPrincipal;
import org.yuktisetu.db.College;
import org.yuktisetu.db.Department;
import org.yuktisetu.model.TenantStatus;
import org.yuktisetu.repository.CollegeRepository;
import org.yuktisetu.repository.DepartmentRepository;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final CollegeRepository collegeRepository;
    private final InstituteHierarchyPolicy policy;

    @Transactional
    public DepartmentResponse create(UserPrincipal actor, DepartmentRequest req) {
        College college = collegeRepository.findByIdAndIsDeletedFalse(req.collegeId())
                .orElseThrow(() -> new AdminExceptions.NotFoundException("College", req.collegeId()));

        // Scope check happens AFTER we know which college, on purpose --
        // a TNP_COLLEGE_ADMIN's authority is scoped per-college, so we can't
        // evaluate "can this actor manage a department" without first
        // knowing which college it would belong to.
        if (!policy.canManageDepartment(actor, college.getId())) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }

        if (college.getStatus() != TenantStatus.ACTIVE) {
            throw new AdminExceptions.ParentNotActiveException("College", college.getId());
        }

        String code = req.code().trim();
        if (departmentRepository.existsByCollegeIdAndCodeIgnoreCaseAndIsDeletedFalse(college.getId(), code)) {
            throw new AdminExceptions.DuplicateCodeException("Department", code);
        }

        Department department = new Department();
        department.setCollege(college);
        department.setName(req.name().trim());
        department.setCode(code);
        department.setStatus(TenantStatus.ACTIVE);
        department.setCreatedBy(actor.userId());
        department.setUpdatedBy(actor.userId());

        return toResponse(departmentRepository.save(department));
    }

    public List<DepartmentResponse> listActiveForCollege(Long collegeId) {
        return departmentRepository.findByCollegeIdAndIsDeletedFalse(collegeId).stream().map(this::toResponse).toList();
    }

    public DepartmentResponse get(Long id) {
        Department department = departmentRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Department", id));
        return toResponse(department);
    }

    @Transactional
    public DepartmentResponse update(UserPrincipal actor, Long id, DepartmentRequest req) {
        Department department = departmentRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Department", id));

        if (!policy.canManageDepartment(actor, department.getCollege().getId())) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }

        String code = req.code().trim();
        if (!code.equalsIgnoreCase(department.getCode())
                && departmentRepository.existsByCollegeIdAndCodeIgnoreCaseAndIsDeletedFalse(department.getCollege().getId(), code)) {
            throw new AdminExceptions.DuplicateCodeException("Department", code);
        }

        department.setName(req.name().trim());
        department.setCode(code);
        department.setUpdatedBy(actor.userId());

        return toResponse(departmentRepository.save(department));
    }

    @Transactional
    public DepartmentResponse setStatus(UserPrincipal actor, Long id, TenantStatus status) {
        Department department = departmentRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Department", id));
        if (!policy.canManageDepartment(actor, department.getCollege().getId())) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        department.setStatus(status);
        department.setUpdatedBy(actor.userId());
        return toResponse(departmentRepository.save(department));
    }

    @Transactional
    public void softDelete(UserPrincipal actor, Long id) {
        Department department = departmentRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Department", id));
        // Restore/soft-delete authority is IT_ADMIN/TNP_SUPER_ADMIN only,
        // same as Trust/College -- NOT the scoped College Admin check used
        // for create/update/status above. See InstituteHierarchyPolicy.canRestore.
        if (!policy.canRestore(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        department.setDeleted(true);
        department.setDeletedAt(Instant.now());
        department.setDeletedBy(actor.userId());
        departmentRepository.save(department);
    }

    @Transactional
    public DepartmentResponse restore(UserPrincipal actor, Long id) {
        if (!policy.canRestore(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Department", id));
        department.setDeleted(false);
        department.setDeletedAt(null);
        department.setDeletedBy(null);
        department.setUpdatedBy(actor.userId());
        return toResponse(departmentRepository.save(department));
    }

    @Transactional
    public void hardDelete(UserPrincipal actor, Long id) {
        if (!policy.canHardDelete(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Department", id));
        if (!department.isDeleted()) {
            throw new AdminExceptions.NotYetDeactivatedException();
        }
        // No further child tier below Department in institute-dal today --
        // if/when a Department-scoped entity is added later, add the same
        // existsByDepartmentId guard used at the Trust/College tiers above.
        departmentRepository.delete(department);
    }

    private DepartmentResponse toResponse(Department d) {
        return new DepartmentResponse(
                d.getId(), d.getCollege().getId(), d.getName(), d.getCode(),
                d.getStatus(), d.getCreatedAt(), d.getUpdatedAt()
        );
    }
}

package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.dto.CompanyContactRequest;
import org.yuktisetu.adminservice.dto.CompanyContactResponse;
import org.yuktisetu.adminservice.dto.CompanyRequest;
import org.yuktisetu.adminservice.dto.CompanyResponse;
import org.yuktisetu.adminservice.dto.CompanyStatusChangeRequest;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.policy.PlacementDrivePolicy;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.Company;
import org.yuktisetu.drive.db.CompanyContact;
import org.yuktisetu.drive.model.CompanyStatus;
import org.yuktisetu.drive.repository.CompanyContactRepository;
import org.yuktisetu.drive.repository.CompanyRepository;
import org.yuktisetu.drive.repository.JobPostingRepository;
import org.yuktisetu.drive.repository.PlacementOfferRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Company onboarding. A company must exist and be ACTIVE before any job can be
 * posted against it.
 *
 * Companies are trust-wide records shared by every PCET college, which is what
 * lets one posting target several colleges and what makes the trust-wide
 * recruiter view meaningful. The cost is that name and code uniqueness are
 * trust-wide too, so onboarding checks before inserting rather than relying on
 * per-college isolation to keep duplicates apart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final CompanyContactRepository companyContactRepository;
    private final JobPostingRepository jobPostingRepository;
    private final PlacementOfferRepository placementOfferRepository;
    private final PlacementDrivePolicy policy;

    @Transactional
    public CompanyResponse create(UserPrincipal actor, CompanyRequest req) {
        try {
            return doCreate(actor, req);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to onboard company code={} actorId={}: {}",
                    req.code(), actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not onboard the company. Please try again.");
        }
    }

    private CompanyResponse doCreate(UserPrincipal actor, CompanyRequest req) {
        if (!policy.canManageCompany(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        String code = req.code().trim();
        if (companyRepository.existsByCodeIgnoreCaseAndIsDeletedFalse(code)) {
            throw new AdminExceptions.DuplicateCodeException("Company", code);
        }
        if (companyRepository.existsByNameIgnoreCaseAndIsDeletedFalse(req.name().trim())) {
            throw new AdminExceptions.DuplicateCodeException("Company", req.name().trim());
        }

        Company company = new Company();
        apply(company, req);
        company.setCode(code);
        company.setStatus(CompanyStatus.PROSPECT);
        company.setCreatedBy(actor.userId());
        company.setUpdatedBy(actor.userId());
        Company saved = companyRepository.save(company);

        if (req.primaryContactEmail() != null && !req.primaryContactEmail().isBlank()) {
            CompanyContact contact = new CompanyContact();
            contact.setCompany(saved);
            contact.setName(req.primaryContactName());
            contact.setDesignation(req.primaryContactDesignation());
            contact.setEmail(req.primaryContactEmail().trim().toLowerCase());
            contact.setPhone(req.primaryContactPhone());
            contact.setPrimary(true);
            contact.setPrimaryContactLock(saved.getId());
            contact.setCreatedBy(actor.userId());
            contact.setUpdatedBy(actor.userId());
            companyContactRepository.save(contact);
        }

        log.info("Created company id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    @Transactional
    public CompanyResponse update(UserPrincipal actor, Long id, CompanyRequest req) {
        try {
            return doUpdate(actor, id, req);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update company id={} actorId={}: {}", id, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not update the company. Please try again.");
        }
    }

    private CompanyResponse doUpdate(UserPrincipal actor, Long id, CompanyRequest req) {
        if (!policy.canManageCompany(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Company company = requireCompany(id);

        String code = req.code().trim();
        if (!company.getCode().equalsIgnoreCase(code)
                && companyRepository.existsByCodeIgnoreCaseAndIsDeletedFalse(code)) {
            throw new AdminExceptions.DuplicateCodeException("Company", code);
        }
        apply(company, req);
        company.setCode(code);
        company.setUpdatedBy(actor.userId());

        log.info("Updated company id={} actorId={}", id, actor.userId());
        return toResponse(companyRepository.save(company));
    }

    /**
     * Status changes, including blacklisting. Blacklisting reaches every college
     * in the trust, so it is gated harder than ordinary management and requires a
     * recorded reason.
     */
    @Transactional
    public CompanyResponse setStatus(UserPrincipal actor, Long id, CompanyStatusChangeRequest req) {
        try {
            return doSetStatus(actor, id, req);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to set company id={} status={} actorId={}: {}",
                    id, req.status(), actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not change the company status. Please try again.");
        }
    }

    private CompanyResponse doSetStatus(UserPrincipal actor, Long id, CompanyStatusChangeRequest req) {
        boolean blacklisting = req.status() == CompanyStatus.BLACKLISTED;
        if (blacklisting ? !policy.canBlacklistCompany(actor) : !policy.canManageCompany(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Company company = requireCompany(id);

        if (blacklisting) {
            if (req.reason() == null || req.reason().isBlank()) {
                throw new AdminExceptions.InvalidRequestException(
                        "A reason is required when blacklisting a company.");
            }
            company.setBlacklistReason(req.reason());
            company.setBlacklistedAt(Instant.now());
        } else if (company.getStatus() == CompanyStatus.BLACKLISTED) {
            // Un-blacklisting clears the record of why, so the field never
            // outlives the state it explains.
            company.setBlacklistReason(null);
            company.setBlacklistedAt(null);
        }

        company.setStatus(req.status());
        company.setUpdatedBy(actor.userId());

        log.info("Company status changed id={} status={} actorId={}", id, req.status(), actor.userId());
        return toResponse(companyRepository.save(company));
    }

    public CompanyResponse get(Long id) {
        try {
            return toResponse(requireCompany(id));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load company id={}: {}", id, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load the company.");
        }
    }

    public Page<CompanyResponse> list(CompanyStatus status, Pageable pageable) {
        try {
            return doList(status, pageable);
        } catch (Exception e) {
            log.error("Failed to list companies status={}: {}", status, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load the company list.");
        }
    }

    private Page<CompanyResponse> doList(CompanyStatus status, Pageable pageable) {
        Page<Company> page = status == null
                ? companyRepository.findByIsDeletedFalse(pageable)
                : companyRepository.findByStatusAndIsDeletedFalse(status, pageable);
        return page.map(this::toResponse);
    }

    @Transactional
    public void softDelete(UserPrincipal actor, Long id) {
        try {
            doSoftDelete(actor, id);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to soft-delete company id={} actorId={}: {}", id, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not delete the company. Please try again.");
        }
    }

    private void doSoftDelete(UserPrincipal actor, Long id) {
        if (!policy.canManageCompany(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Company company = requireCompany(id);
        company.setDeleted(true);
        company.setDeletedAt(Instant.now());
        company.setDeletedBy(actor.userId());
        companyRepository.save(company);
        log.info("Soft-deleted company id={} actorId={}", id, actor.userId());
    }

    @Transactional
    public CompanyResponse restore(UserPrincipal actor, Long id) {
        try {
            return doRestore(actor, id);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to restore company id={} actorId={}: {}", id, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not restore the company. Please try again.");
        }
    }

    private CompanyResponse doRestore(UserPrincipal actor, Long id) {
        if (!policy.canRestoreCompany(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Company", id));
        company.setDeleted(false);
        company.setDeletedAt(null);
        company.setDeletedBy(null);
        company.setUpdatedBy(actor.userId());
        log.info("Restored company id={} actorId={}", id, actor.userId());
        return toResponse(companyRepository.save(company));
    }

    /**
     * Permanent removal. Refused while anything still points at the company --
     * postings and offers are historical placement records, and cascading them
     * away to tidy up a company row would erase a college's own statistics.
     */
    @Transactional
    public void hardDelete(UserPrincipal actor, Long id) {
        try {
            doHardDelete(actor, id);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to hard-delete company id={} actorId={}: {}", id, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException(
                    "Could not permanently delete the company. Please try again.");
        }
    }

    private void doHardDelete(UserPrincipal actor, Long id) {
        if (!policy.canHardDelete(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Company", id));
        if (!company.isDeleted()) {
            throw new AdminExceptions.NotYetDeactivatedException();
        }
        if (jobPostingRepository.countByCompany_Id(id) > 0) {
            throw new AdminExceptions.HasDependentRecordsException("Company", id, "JobPosting");
        }
        if (placementOfferRepository.countByCompany_Id(id) > 0) {
            throw new AdminExceptions.HasDependentRecordsException("Company", id, "PlacementOffer");
        }
        companyContactRepository.deleteAll(companyContactRepository.findByCompany_IdAndIsDeletedFalse(id));
        companyRepository.delete(company);
        log.warn("Company PERMANENTLY deleted id={} code={} actorId={}", id, company.getCode(), actor.userId());
    }

    // --- Contacts ---

    @Transactional
    public CompanyContactResponse addContact(UserPrincipal actor, Long companyId, CompanyContactRequest req) {
        try {
            return doAddContact(actor, companyId, req);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to add contact to company id={} actorId={}: {}",
                    companyId, actor.userId(), e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not add the contact. Please try again.");
        }
    }

    private CompanyContactResponse doAddContact(UserPrincipal actor, Long companyId, CompanyContactRequest req) {
        if (!policy.canManageCompany(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        Company company = requireCompany(companyId);
        String email = req.email().trim().toLowerCase();
        if (companyContactRepository.existsByCompany_IdAndEmailIgnoreCaseAndIsDeletedFalse(companyId, email)) {
            throw new AdminExceptions.DuplicateCodeException("CompanyContact", email);
        }

        boolean wantsPrimary = Boolean.TRUE.equals(req.isPrimary());
        if (wantsPrimary) {
            demoteExistingPrimary(companyId, actor);
        }

        CompanyContact contact = new CompanyContact();
        contact.setCompany(company);
        contact.setName(req.name());
        contact.setDesignation(req.designation());
        contact.setEmail(email);
        contact.setPhone(req.phone());
        contact.setNotes(req.notes());
        contact.setPrimary(wantsPrimary);
        contact.setPrimaryContactLock(wantsPrimary ? companyId : null);
        contact.setCreatedBy(actor.userId());
        contact.setUpdatedBy(actor.userId());

        CompanyContact saved = companyContactRepository.save(contact);
        log.info("Added contact id={} to company id={} actorId={}", saved.getId(), companyId, actor.userId());
        return toResponse(saved);
    }

    public List<CompanyContactResponse> listContacts(Long companyId) {
        try {
            requireCompany(companyId);
            List<CompanyContact> contacts =
                    companyContactRepository.findByCompany_IdAndIsDeletedFalse(companyId);
            List<CompanyContactResponse> out = new ArrayList<>(contacts.size());
            for (CompanyContact c : contacts) {
                out.add(toResponse(c));
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to list contacts for company id={}: {}", companyId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not load the company contacts.");
        }
    }

    @Transactional
    public void removeContact(UserPrincipal actor, Long companyId, Long contactId) {
        try {
            doRemoveContact(actor, companyId, contactId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to remove contact id={} from company id={}: {}",
                    contactId, companyId, e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not remove the contact. Please try again.");
        }
    }

    private void doRemoveContact(UserPrincipal actor, Long companyId, Long contactId) {
        if (!policy.canManageCompany(actor)) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
        CompanyContact contact = companyContactRepository.findByIdAndIsDeletedFalse(contactId)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("CompanyContact", contactId));
        if (!contact.getCompany().getId().equals(companyId)) {
            throw new AdminExceptions.NotFoundException("CompanyContact", contactId);
        }
        contact.setDeleted(true);
        contact.setDeletedAt(Instant.now());
        contact.setDeletedBy(actor.userId());
        // Release the primary slot, or the next primary contact collides with a
        // soft-deleted row on uk_company_contact_primary.
        contact.setPrimary(false);
        contact.setPrimaryContactLock(null);
        companyContactRepository.save(contact);
        log.info("Removed contact id={} from company id={} actorId={}", contactId, companyId, actor.userId());
    }

    private void demoteExistingPrimary(Long companyId, UserPrincipal actor) {
        companyContactRepository.findByCompany_IdAndIsPrimaryTrueAndIsDeletedFalse(companyId)
                .ifPresent(existing -> {
                    existing.setPrimary(false);
                    existing.setPrimaryContactLock(null);
                    existing.setUpdatedBy(actor.userId());
                    // Flushed before the new primary is inserted, otherwise both
                    // rows briefly hold the same primary_contact_lock value and
                    // the unique constraint rejects the insert.
                    companyContactRepository.saveAndFlush(existing);
                });
    }

    private Company requireCompany(Long id) {
        return companyRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new AdminExceptions.NotFoundException("Company", id));
    }

    private void apply(Company company, CompanyRequest req) {
        company.setName(req.name().trim());
        company.setLegalName(req.legalName());
        company.setCompanyType(req.companyType());
        company.setTier(req.tier());
        company.setWebsite(req.website());
        company.setIndustry(req.industry());
        company.setLogoUrl(req.logoUrl());
        company.setDescription(req.description());
        company.setHqCity(req.hqCity());
        company.setHqState(req.hqState());
        company.setHqCountry(req.hqCountry());
        company.setRegistrationNumber(req.registrationNumber());
    }

    private CompanyResponse toResponse(Company c) {
        return new CompanyResponse(
                c.getId(), c.getName(), c.getLegalName(), c.getCode(),
                c.getCompanyType(), c.getTier(), c.getStatus(),
                c.getWebsite(), c.getIndustry(), c.getLogoUrl(), c.getDescription(),
                c.getHqCity(), c.getHqState(), c.getHqCountry(), c.getRegistrationNumber(),
                c.getBlacklistReason(), c.getBlacklistedAt(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    private CompanyContactResponse toResponse(CompanyContact c) {
        return new CompanyContactResponse(
                c.getId(), c.getCompany().getId(), c.getName(), c.getDesignation(),
                c.getEmail(), c.getPhone(), c.isPrimary(), c.getNotes(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}

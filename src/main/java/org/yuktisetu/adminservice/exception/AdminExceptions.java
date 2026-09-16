package org.yuktisetu.adminservice.exception;

import org.springframework.http.HttpStatus;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.exception.ConflictException;

public final class AdminExceptions {

    private AdminExceptions() {}

    public static class InsufficientAuthorityException extends ApiException {
        public InsufficientAuthorityException() {
            super(HttpStatus.FORBIDDEN, "INSUFFICIENT_AUTHORITY", "Actor role is not permitted to perform this action.");
        }
    }

    public static class NotFoundException extends org.yuktisetu.core.exception.NotFoundException {
        public NotFoundException(String entity, Long id) {
            super(entity, id);
        }
    }

    public static class DuplicateCodeException extends ConflictException {
        public DuplicateCodeException(String entity, String code) {
            super("DUPLICATE_CODE", entity + " code '" + code + "' already exists in this scope.");
        }
    }

    // Thrown creating a College under an INACTIVE/soft-deleted Trust, or a
    // Department under an INACTIVE/soft-deleted College. Structure must be
    // built top-down while active -- an inactive parent means the tenant
    // itself is paused/left, and new children under it would silently
    // resurrect it in every listing that filters by isDeleted alone without
    // also checking status.
    public static class ParentNotActiveException extends ConflictException {
        public ParentNotActiveException(String parentEntity, Long parentId) {
            super("PARENT_NOT_ACTIVE", parentEntity + " " + parentId + " is not ACTIVE -- cannot attach a new child record to it.");
        }
    }

    // Thrown deactivating/hard-deleting a record while active or existing
    // children still hang off it. See InstituteHierarchyPolicy javadoc --
    // this is a stricter-than-specified default, not an SSOT citation.
    public static class HasDependentRecordsException extends ConflictException {
        public HasDependentRecordsException(String parentEntity, Long parentId, String childEntity) {
            super("HAS_DEPENDENT_RECORDS", parentEntity + " " + parentId + " still has " + childEntity
                    + " records attached -- resolve those first.");
        }
    }

    public static class NotYetDeactivatedException extends ConflictException {
        public NotYetDeactivatedException() {
            super("NOT_YET_DEACTIVATED", "Target must be soft-deleted/deactivated before it can be hard-deleted.");
        }
    }

    // --- Placement drive ---

    /** The posting is not in a status where this move is legal. */
    public static class IllegalStatusTransitionException extends ConflictException {
        public IllegalStatusTransitionException(String entity, String from, String to) {
            super("ILLEGAL_STATUS_TRANSITION",
                    entity + " cannot move from " + from + " to " + to + ".");
        }
    }

    /** Content edits are only legal while a posting is still a draft. */
    public static class PostingNotEditableException extends ConflictException {
        public PostingNotEditableException(String status) {
            super("POSTING_NOT_EDITABLE",
                    "A posting in status " + status + " can no longer be edited. "
                            + "Cancel it and create a new one if the terms have changed.");
        }
    }

    /**
     * Publish refused, naming the precondition that failed. Publishing is the
     * irreversible step -- it notifies students and opens applications -- so the
     * caller gets told exactly what is missing rather than a generic 409.
     */
    public static class PostingNotPublishableException extends ConflictException {
        public PostingNotPublishableException(String reason) {
            super("POSTING_NOT_PUBLISHABLE", "This posting cannot be published: " + reason);
        }
    }

    public static class CompanyNotActiveException extends ConflictException {
        public CompanyNotActiveException(String companyName, String status) {
            super("COMPANY_NOT_ACTIVE",
                    "Company '" + companyName + "' is " + status + " and cannot receive new postings.");
        }
    }

    /** An enabled eligibility gate with no threshold set would silently pass everyone. */
    public static class CriteriaIncompleteException extends ApiException {
        public CriteriaIncompleteException(String criterion) {
            super(HttpStatus.BAD_REQUEST, "CRITERIA_INCOMPLETE",
                    "Criterion '" + criterion + "' is enabled but has no threshold value.");
        }
    }

    public static class DuplicateTargetException extends ConflictException {
        public DuplicateTargetException(String targetKey) {
            super("DUPLICATE_TARGET", "This posting already targets " + targetKey + ".");
        }
    }

    /** Offer status moves must follow OfferStatusTransitions. */
    public static class IllegalOfferTransitionException extends ConflictException {
        public IllegalOfferTransitionException(String from, String to) {
            super("ILLEGAL_OFFER_TRANSITION", "An offer cannot move from " + from + " to " + to + ".");
        }
    }

    /** The DB-level internship lock rejected a second live internship offer. */
    public static class LiveInternshipExistsException extends ConflictException {
        public LiveInternshipExistsException() {
            super("LIVE_INTERNSHIP_EXISTS",
                    "This student already holds a live internship offer. "
                            + "Revoke, decline or lapse it before recording another.");
        }
    }

    public static class InvalidRequestException extends ApiException {
        public InvalidRequestException(String message) {
            super(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
        }
    }
}

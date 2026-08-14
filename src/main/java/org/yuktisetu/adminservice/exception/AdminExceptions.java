package org.yuktisetu.adminservice.exception;

public final class AdminExceptions {

    private AdminExceptions() {}

    public static class InsufficientAuthorityException extends RuntimeException {
        public InsufficientAuthorityException() {
            super("Actor role is not permitted to perform this action.");
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String entity, Long id) {
            super(entity + " with id " + id + " not found (or already deleted).");
        }
    }

    public static class DuplicateCodeException extends RuntimeException {
        public DuplicateCodeException(String entity, String code) {
            super(entity + " code '" + code + "' already exists in this scope.");
        }
    }

    // Thrown creating a College under an INACTIVE/soft-deleted Trust, or a
    // Department under an INACTIVE/soft-deleted College. Structure must be
    // built top-down while active -- an inactive parent means the tenant
    // itself is paused/left, and new children under it would silently
    // resurrect it in every listing that filters by isDeleted alone without
    // also checking status.
    public static class ParentNotActiveException extends RuntimeException {
        public ParentNotActiveException(String parentEntity, Long parentId) {
            super(parentEntity + " " + parentId + " is not ACTIVE -- cannot attach a new child record to it.");
        }
    }

    // Thrown deactivating/hard-deleting a record while active or existing
    // children still hang off it. See InstituteHierarchyPolicy javadoc --
    // this is a stricter-than-specified default, not an SSOT citation.
    public static class HasDependentRecordsException extends RuntimeException {
        public HasDependentRecordsException(String parentEntity, Long parentId, String childEntity) {
            super(parentEntity + " " + parentId + " still has " + childEntity
                    + " records attached -- resolve those first.");
        }
    }

    public static class NotYetDeactivatedException extends RuntimeException {
        public NotYetDeactivatedException() {
            super("Target must be soft-deleted/deactivated before it can be hard-deleted.");
        }
    }

    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }
}

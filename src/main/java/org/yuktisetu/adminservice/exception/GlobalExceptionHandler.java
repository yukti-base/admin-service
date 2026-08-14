package org.yuktisetu.adminservice.exception;

import org.yuktisetu.adminservice.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AdminExceptions.InsufficientAuthorityException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientAuthority(AdminExceptions.InsufficientAuthorityException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("INSUFFICIENT_AUTHORITY", ex.getMessage()));
    }

    @ExceptionHandler(AdminExceptions.NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(AdminExceptions.NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(AdminExceptions.DuplicateCodeException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCode(AdminExceptions.DuplicateCodeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_CODE", ex.getMessage()));
    }

    @ExceptionHandler(AdminExceptions.ParentNotActiveException.class)
    public ResponseEntity<ErrorResponse> handleParentNotActive(AdminExceptions.ParentNotActiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("PARENT_NOT_ACTIVE", ex.getMessage()));
    }

    @ExceptionHandler(AdminExceptions.HasDependentRecordsException.class)
    public ResponseEntity<ErrorResponse> handleHasDependents(AdminExceptions.HasDependentRecordsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("HAS_DEPENDENT_RECORDS", ex.getMessage()));
    }

    @ExceptionHandler(AdminExceptions.NotYetDeactivatedException.class)
    public ResponseEntity<ErrorResponse> handleNotYetDeactivated(AdminExceptions.NotYetDeactivatedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("NOT_YET_DEACTIVATED", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", "Request payload failed validation"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // Never return ex.getMessage() here for unknown exceptions -- that's
        // how stack traces / SQL fragments leak to clients. Log server-side.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "Something went wrong"));
    }
}

package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.yuktisetu.model.PostingDocumentType;

/**
 * Registers a document already uploaded to object storage.
 *
 * The bytes do not pass through this service: the caller uploads to storage and
 * registers the resulting key here. That keeps the storage backend -- still
 * undecided -- swappable without touching this API, and keeps a multi-megabyte
 * JD off the admin-service request path.
 */
public record PostingDocumentRequest(
        @NotNull PostingDocumentType docType,
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank @Size(max = 512) String storageKey,
        @Size(max = 128) String contentType,
        Long sizeBytes,
        Boolean primary
) {}

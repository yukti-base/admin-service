package org.yuktisetu.adminservice.dto;

import org.yuktisetu.drive.model.PostingDocumentType;

import java.time.Instant;

public record PostingDocumentResponse(
        Long id,
        Long postingId,
        PostingDocumentType docType,
        String fileName,
        String storageKey,
        /** Directly openable; null for rows registered before uploads were supported. */
        String url,
        String contentType,
        Long sizeBytes,
        boolean primary,
        Long uploadedBy,
        Instant createdAt
) {}

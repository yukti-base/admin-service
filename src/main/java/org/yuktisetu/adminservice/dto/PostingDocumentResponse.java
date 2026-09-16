package org.yuktisetu.adminservice.dto;

import org.yuktisetu.model.PostingDocumentType;

import java.time.Instant;

public record PostingDocumentResponse(
        Long id,
        Long postingId,
        PostingDocumentType docType,
        String fileName,
        String storageKey,
        String contentType,
        Long sizeBytes,
        boolean primary,
        Long uploadedBy,
        Instant createdAt
) {}

package com.rag.springai.insuranceai.application.document;

import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentType;

import java.time.Instant;

/**
 * Registers a new {@code Document} together with its first {@code DocumentVersion}. Carries
 * raw content bytes so the use case can compute the {@code ContentHash} itself - callers never
 * compute hashes independently (brief section 6).
 */
public record RegisterDocumentCommand(String name, DocumentType type, DocumentMetadata metadata, byte[] content,
        Instant effectiveFrom) {
}

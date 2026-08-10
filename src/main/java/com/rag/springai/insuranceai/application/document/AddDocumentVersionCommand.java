package com.rag.springai.insuranceai.application.document;

import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.EffectivePeriod;
import com.rag.springai.insuranceai.domain.document.VersionNumber;

/**
 * Adds a new version to an already-registered {@code Document}.
 */
public record AddDocumentVersionCommand(DocumentId documentId, VersionNumber versionNumber, byte[] content,
        EffectivePeriod effectivePeriod) {
}

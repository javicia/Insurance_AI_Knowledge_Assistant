package com.rag.springai.insuranceai.domain.document.exception;

import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import com.rag.springai.insuranceai.domain.shared.exception.DomainException;

/**
 * Raised when adding a {@code DocumentVersion} whose {@code VersionNumber} is already used by
 * another version of the same {@code Document}.
 */
public final class DuplicateVersionNumberException extends DomainException {

    public DuplicateVersionNumberException(DocumentId documentId, VersionNumber versionNumber) {
        super("DOCUMENT_VERSION_DUPLICATE_NUMBER",
                "Version " + versionNumber + " already exists for document " + documentId);
    }
}

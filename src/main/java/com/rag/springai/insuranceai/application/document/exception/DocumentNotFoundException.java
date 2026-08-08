package com.rag.springai.insuranceai.application.document.exception;

import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.document.DocumentId;

/**
 * Raised when a use case is given a {@code DocumentId} that does not resolve to any stored
 * {@code Document}. A use-case orchestration failure, not a domain-rule violation - see
 * {@code ApplicationException}'s Javadoc.
 */
public final class DocumentNotFoundException extends ApplicationException {

    public DocumentNotFoundException(DocumentId documentId) {
        super("DOCUMENT_NOT_FOUND", "No document found with id " + documentId);
    }
}

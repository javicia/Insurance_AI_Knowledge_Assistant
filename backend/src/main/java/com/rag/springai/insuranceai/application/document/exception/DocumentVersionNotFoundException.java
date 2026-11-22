package com.rag.springai.insuranceai.application.document.exception;

import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;

/**
 * Raised when a use case is given a {@code DocumentVersionId} that does not exist within the
 * given document.
 */
public final class DocumentVersionNotFoundException extends ApplicationException {

    public DocumentVersionNotFoundException(DocumentId documentId, DocumentVersionId documentVersionId) {
        super("DOCUMENT_VERSION_NOT_FOUND",
                "No version " + documentVersionId + " found for document " + documentId);
    }
}

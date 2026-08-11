package com.rag.springai.insuranceai.domain.document.exception;

import com.rag.springai.insuranceai.domain.document.DocumentStatus;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.shared.exception.DomainException;

/**
 * Raised when a {@code DocumentVersion} lifecycle transition does not follow
 * {@link DocumentStatus#canTransitionTo(DocumentStatus)}.
 */
public final class InvalidDocumentVersionTransitionException extends DomainException {

    public InvalidDocumentVersionTransitionException(DocumentVersionId versionId, DocumentStatus from,
            DocumentStatus to) {
        super("DOCUMENT_VERSION_INVALID_TRANSITION",
                "Document version " + versionId + " cannot transition from " + from + " to " + to);
    }
}

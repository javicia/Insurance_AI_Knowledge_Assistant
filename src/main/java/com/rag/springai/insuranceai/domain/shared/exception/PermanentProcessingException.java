package com.rag.springai.insuranceai.domain.shared.exception;

/**
 * A technical failure that no retry can fix (unparseable PDF, unsupported content, empty
 * extracted text). Caught explicitly by the application layer, which marks the
 * {@code DocumentVersion} as {@code FAILED} (see {@code ProcessDocumentVersionUseCase}), so the
 * Kafka consumer acknowledges the message instead of retrying it. See
 * {@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}.
 */
public final class PermanentProcessingException extends InfrastructureException {

    public PermanentProcessingException(String errorCode, String message) {
        super(errorCode, message);
    }

    public PermanentProcessingException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}

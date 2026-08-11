package com.rag.springai.insuranceai.domain.shared.exception;

/**
 * A technical failure expected to be retry-safe (I/O timeout, connection drop, transient
 * provider error). Deliberately left uncaught by inbound Kafka consumers so Spring Kafka's
 * error handler retries the record with backoff; never causes a {@code DocumentVersion} to be
 * marked {@code FAILED}. See {@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}.
 */
public final class TransientProcessingException extends InfrastructureException {

    public TransientProcessingException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}

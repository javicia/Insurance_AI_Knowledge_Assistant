package com.rag.springai.insuranceai.adapters.shared.exception;

/**
 * Base type for technical failures raised by adapters (database, messaging, vector store or
 * LLM provider errors) once those adapters are implemented in later phases — the
 * "infrastructure exceptions" category named in brief section 52. Named after that category
 * rather than the {@code infrastructure} package: it is thrown and caught by classes in the
 * {@code adapters} layer (outbound adapters raise it, {@code GlobalExceptionHandler} in
 * {@code adapters.inbound.rest} maps it to an HTTP response), so it lives alongside them
 * rather than in the composition-root {@code infrastructure} layer, which nothing else may
 * depend on (ADR-001).
 */
public abstract class InfrastructureException extends RuntimeException {

    private final String errorCode;

    protected InfrastructureException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected InfrastructureException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

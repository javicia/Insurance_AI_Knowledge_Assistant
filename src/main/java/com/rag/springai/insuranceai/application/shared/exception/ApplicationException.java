package com.rag.springai.insuranceai.application.shared.exception;

/**
 * Base type for use-case orchestration failures that are not domain-rule violations (e.g. a
 * query that legitimately cannot be completed, such as the RAG no-answer strategy in brief
 * section 53). Distinct from {@code DomainException} and {@code InfrastructureException} per
 * brief section 52.
 */
public abstract class ApplicationException extends RuntimeException {

    private final String errorCode;

    protected ApplicationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected ApplicationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

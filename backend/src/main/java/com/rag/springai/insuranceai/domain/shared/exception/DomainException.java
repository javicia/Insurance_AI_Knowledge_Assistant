package com.rag.springai.insuranceai.domain.shared.exception;

/**
 * Base type for violations of a domain rule or invariant (e.g. an aggregate refusing an
 * invalid state transition). Distinct from {@code ApplicationException} (use-case
 * orchestration failures) and {@code InfrastructureException} (technical/adapter failures)
 * per brief section 52.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected DomainException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

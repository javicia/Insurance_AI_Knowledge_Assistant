package com.rag.springai.insuranceai.domain.shared.exception;

/**
 * Base type for technical failures raised by adapters (database, messaging, vector store or
 * LLM provider errors) - the "infrastructure exceptions" category named in brief section 52.
 * Lives in {@code domain.shared.exception}, not {@code adapters}, even though only adapters
 * throw it: outbound adapters raise it (e.g. {@code PdfBoxTextExtractor}) and both the
 * application layer (which decides how to react - see
 * {@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}) and inbound adapters (e.g.
 * {@code GlobalExceptionHandler}) need to catch it. Domain is the only layer every other layer
 * is allowed to depend on (ADR-001), so a shared exception contract crossing adapters and
 * application must live there, not in {@code adapters.shared} as FASE 1 originally placed it.
 * Being a plain {@code RuntimeException} subclass with no framework dependency, it is valid
 * pure-domain code.
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

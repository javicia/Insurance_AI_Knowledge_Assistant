package com.rag.springai.insuranceai.domain.shared.exception;

/**
 * A technical failure that no retry can fix. Two current sources: document processing
 * (unparseable PDF, unsupported content, empty extracted text) - caught explicitly by the
 * application layer, which marks the {@code DocumentVersion} as {@code FAILED} (see {@code
 * ProcessDocumentVersionUseCase}), so the Kafka consumer acknowledges the message instead of
 * retrying it (see {@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}) - and an LLM
 * provider rejecting a request outright (invalid credentials, malformed request, rate limit
 * exhausted - see {@code OpenAiLlmAdapter}/{@code AnthropicLlmAdapter}, FASE 11, {@code
 * docs/resilience/RESILIENCE.md}), which {@code GlobalExceptionHandler} maps to {@code 502 Bad
 * Gateway} rather than {@link TransientProcessingException}'s {@code 503}, since retrying later
 * would not help.
 */
public final class PermanentProcessingException extends InfrastructureException {

    public PermanentProcessingException(String errorCode, String message) {
        super(errorCode, message);
    }

    public PermanentProcessingException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}

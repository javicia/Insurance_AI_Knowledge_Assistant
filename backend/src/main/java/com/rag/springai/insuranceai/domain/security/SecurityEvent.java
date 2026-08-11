package com.rag.springai.insuranceai.domain.security;

import java.time.Instant;
import java.util.Objects;

/**
 * FASE 23: a structured security event (brief: "eventos mínimos: authentication failure,
 * authorization failure, rate limit exceeded, prompt injection blocked, PII detection, suspicious
 * request, admin operation, audit lookup, configuration/security changes"). ECS
 * (Elastic Common Schema)-*inspired* field naming (`event.kind`/`event.category`/`event.type`/
 * `event.outcome`) - chosen because it is a widely recognized, tool-agnostic convention, not
 * because this integrates with a real Elastic/SIEM deployment (none exists in this PoC - see
 * {@code docs/security/SIEM.md} for exactly where that boundary is).
 *
 * <p><b>Never carries</b>: an {@code Authorization} header, an access/refresh token, a password,
 * a full prompt, a full LLM answer, or raw PII - {@code reason} is a short, pre-defined category
 * string (e.g. {@code "invalid_signature"}), never free-form user input echoed back.
 *
 * <p>Lives in the domain layer (not {@code infrastructure}) because the application layer's own
 * use cases (e.g. {@code AskInsuranceKnowledgeUseCase}) must be able to raise this event through
 * {@link com.rag.springai.insuranceai.ports.outbound.SecurityEventPort} without depending on
 * {@code infrastructure} (ADR-001, enforced by {@code ArchitectureTest}).
 */
public record SecurityEvent(Instant timestamp, SecurityEventType type, SecurityEventOutcome outcome, String traceId,
        String principalId, String httpMethod, String path, Integer httpStatus, String reason) {

    public SecurityEvent {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        // principalId/httpMethod/path/httpStatus/reason may legitimately be null - not every
        // event type has an HTTP request in scope (e.g. PII_DETECTED originates from the RAG
        // pipeline, not directly from a servlet request), and no authenticated principal exists
        // yet for an AUTHENTICATION_FAILURE.
    }

    public static SecurityEvent now(SecurityEventType type, SecurityEventOutcome outcome, String traceId,
            String principalId, String httpMethod, String path, Integer httpStatus, String reason) {
        return new SecurityEvent(Instant.now(), type, outcome, traceId, principalId, httpMethod, path, httpStatus,
                reason);
    }
}

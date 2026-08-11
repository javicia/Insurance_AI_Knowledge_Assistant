package com.rag.springai.insuranceai.domain.audit;

/** How a {@code POST /api/chat} request concluded (brief FASE 9 section 18). */
public enum AuditOutcome {
    GROUNDED_ANSWER,
    NO_ANSWER,
    BLOCKED_BY_GUARDRAIL,
    ERROR
}

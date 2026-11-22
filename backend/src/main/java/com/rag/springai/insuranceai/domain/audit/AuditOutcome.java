package com.rag.springai.insuranceai.domain.audit;

/** How a {@code POST /api/chat} request concluded (brief FASE 9 section 18). */
public enum AuditOutcome {
    GROUNDED_ANSWER,

    /**
     * Answered from the model's general knowledge, with no documentary grounding (FASE 28). A
     * distinct outcome rather than a flag on {@link #GROUNDED_ANSWER}: an AI Act audit trail that
     * cannot separate "this is what our documentation says" from "this is what a language model
     * happens to know" does not describe what the system actually did.
     */
    GENERAL_AI_ANSWER,
    NO_ANSWER,
    BLOCKED_BY_GUARDRAIL,
    ERROR
}

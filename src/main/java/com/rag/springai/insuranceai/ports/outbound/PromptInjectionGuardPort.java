package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.security.PromptInjectionAssessment;

/**
 * Outbound port for prompt injection detection (brief FASE 8 section 9/22). A port because the
 * initial rule-based implementation ({@code RuleBasedPromptInjectionGuard}) is explicitly meant
 * to be swappable for a real classifier later (same pattern as {@code RerankerPort} - see
 * {@code docs/adr/ADR-006-ADVANCED-RAG-RETRIEVAL-STRATEGY.md} decision 4 for the precedent).
 */
public interface PromptInjectionGuardPort {

    PromptInjectionAssessment scan(String text);
}

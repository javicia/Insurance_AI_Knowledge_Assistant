package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.security.PiiAssessment;

/**
 * Outbound port for PII detection/redaction (brief FASE 8 section 9/26). A port for the same
 * reason as {@link PromptInjectionGuardPort} - the rule-based PoC implementation is meant to be
 * swappable for a real detector (e.g. Microsoft Presidio, AWS Comprehend) later without callers
 * changing.
 */
public interface PiiGuardPort {

    PiiAssessment scan(String text);

    /** Returns {@code text} with every detected PII occurrence replaced by {@code [REDACTED:TYPE]}. */
    String redact(String text);
}

package com.rag.springai.insuranceai.domain.document;

/**
 * Data sensitivity level of a {@link Document}, used later (FASE 8+) to decide how its content
 * may be handled by retrieval and guardrail components.
 */
public enum DocumentClassification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED
}

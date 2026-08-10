package com.rag.springai.insuranceai.domain.aisystem;

/**
 * Risk tier assigned to an {@link AiSystem} (brief FASE 9 section 10/11). Named after the
 * EU AI Act's own risk tiers because that vocabulary is what stakeholders will expect, but
 * assigning one of these values here is a PoC-level self-assessment, not a legal AI Act
 * determination - see {@code docs/governance/AI_ACT.md}. Any real classification requires
 * legal/compliance review.
 */
public enum RiskClassification {
    MINIMAL,
    LIMITED,
    HIGH,
    UNACCEPTABLE
}

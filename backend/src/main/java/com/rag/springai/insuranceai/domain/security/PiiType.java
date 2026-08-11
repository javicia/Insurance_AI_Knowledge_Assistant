package com.rag.springai.insuranceai.domain.security;

/**
 * Categories of personal data or secrets {@code RuleBasedPiiGuard} recognizes (PoC-level,
 * regex-based - see that class's Javadoc and {@code docs/security/PII.md} for exactly what each
 * pattern does and does not catch, including known false positive/negative rates).
 *
 * <p>{@code CREDIT_CARD}/{@code API_KEY}/{@code JWT}/{@code CREDENTIAL} are not strictly "personal
 * data" in the GDPR sense the first four types are - they are secrets that must never appear in
 * logs or persisted audit data either, so they share this same detector/port rather than a
 * separate one (brief FASE 24: "API KEY, JWT, PASSWORD/SECRET" are explicitly required alongside
 * the PII types proper).
 */
public enum PiiType {
    EMAIL,
    PHONE,
    IBAN,
    NATIONAL_ID,
    CREDIT_CARD,
    API_KEY,
    JWT,
    CREDENTIAL
}

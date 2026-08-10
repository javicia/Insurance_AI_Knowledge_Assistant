package com.rag.springai.insuranceai.domain.security;

/** Categories of personal data {@code RuleBasedPiiGuard} recognizes (PoC-level, regex-based). */
public enum PiiType {
    EMAIL,
    PHONE,
    IBAN,
    NATIONAL_ID
}

package com.rag.springai.insuranceai.domain.security;

/** FASE 23: the minimum event set the brief requires, plus AUDIT_ACCESS (audit lookups are
 *  themselves security-relevant - who looked up what execution record, and when). */
public enum SecurityEventType {
    AUTHENTICATION_FAILURE,
    AUTHORIZATION_DENIED,
    INVALID_TOKEN,
    TOKEN_EXPIRED,
    RATE_LIMIT_EXCEEDED,
    WAF_BLOCK,
    PII_DETECTED,
    PROMPT_INJECTION_BLOCKED,
    SECURITY_CONFIGURATION_ERROR,
    AUDIT_ACCESS
}

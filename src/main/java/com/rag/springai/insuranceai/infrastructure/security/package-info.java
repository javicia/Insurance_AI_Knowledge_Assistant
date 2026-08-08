/**
 * Security wiring for GenAI-specific controls (prompt injection guard, PII detector/
 * sanitizer, output guardrails — brief sections 19-21). Implemented starting FASE 8
 * (GenAI Security) of the delivery plan in {@code PROJECT_DISCOVERY.md}. This is not
 * general application security (no IAM/OAuth2/Keycloak is introduced — see brief section 3
 * and {@code docs/adr/}).
 */
package com.rag.springai.insuranceai.infrastructure.security;

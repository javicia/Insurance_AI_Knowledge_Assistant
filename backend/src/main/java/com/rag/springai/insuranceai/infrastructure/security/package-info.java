/**
 * Security wiring for two distinct concerns, both deliberately kept in this one
 * infrastructure-layer package: GenAI-specific controls (prompt injection guard, PII detector/
 * sanitizer, output guardrails — brief sections 19-21, FASE 8) and, since FASE 17, general
 * application IAM (Spring Security OAuth2 Resource Server validating JWTs issued by Keycloak —
 * see {@code SecurityConfiguration}, {@code docs/security/IAM_ARCHITECTURE.md}, and
 * {@code docs/adr/ADR-015-IAM-OAUTH2-OIDC.md}). Neither concern reaches into {@code domain} or
 * {@code application}, which know nothing of Spring Security, JWTs, or Keycloak - only whichever
 * ports they already depend on.
 */
package com.rag.springai.insuranceai.infrastructure.security;

/**
 * GenAI security bounded context (FASE 8): prompt injection and PII detection assessments.
 * Framework-free value objects only - {@link com.rag.springai.insuranceai.domain.security.PiiMatch}
 * never carries raw sensitive data, only its type and a masked representation (data
 * minimization). See {@code docs/security/SECURITY.md}.
 */
package com.rag.springai.insuranceai.domain.security;

package com.rag.springai.insuranceai.adapters.inbound.rest;

/**
 * Consistent API error shape (brief section 52). Never carries a stack trace or internal
 * implementation detail.
 */
public record ErrorResponse(String code, String message, String traceId) {
}

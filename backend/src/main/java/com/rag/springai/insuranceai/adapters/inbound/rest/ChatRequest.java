package com.rag.springai.insuranceai.adapters.inbound.rest;

/**
 * {@code filters} is optional (FASE 6, brief section 26/27) - a plain {@code {"question": "..."}}
 * body (FASE 5's original contract) still works, since a missing JSON field deserializes to
 * {@code null} and {@link ChatController} treats that as "no restriction".
 */
record ChatRequest(String question, ChatFilterRequest filters) {
}

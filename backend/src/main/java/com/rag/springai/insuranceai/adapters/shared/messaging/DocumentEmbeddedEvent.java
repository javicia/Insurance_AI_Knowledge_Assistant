package com.rag.springai.insuranceai.adapters.shared.messaging;

/**
 * Wire payload for the {@code insurance.document.embedded} Kafka topic (FASE 5 - the first
 * event that actually uses this topic name, previously reserved but unused since FASE 4).
 */
public record DocumentEmbeddedEvent(String documentId, String documentVersionId, int chunkCount) {
}

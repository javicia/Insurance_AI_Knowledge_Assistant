package com.rag.springai.insuranceai.adapters.shared.messaging;

/**
 * Wire payload for the {@code insurance.document.processed} Kafka topic. There is
 * deliberately no {@code DocumentEmbeddedEvent} yet - embeddings are FASE 5 (brief section 5).
 */
public record DocumentProcessedEvent(String documentId, String documentVersionId, int chunkCount) {
}

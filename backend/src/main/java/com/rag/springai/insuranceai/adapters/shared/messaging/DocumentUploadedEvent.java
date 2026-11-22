package com.rag.springai.insuranceai.adapters.shared.messaging;

/**
 * Wire payload for the {@code insurance.document.uploaded} Kafka topic. An adapter-owned DTO,
 * not a domain type: published by {@code adapters.outbound.messaging}, consumed by
 * {@code adapters.inbound.kafka}.
 */
public record DocumentUploadedEvent(String documentId, String documentVersionId, String contentHash) {
}

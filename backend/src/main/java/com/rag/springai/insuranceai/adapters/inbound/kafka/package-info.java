/**
 * Inbound Kafka adapters: consumers for the document-ingestion event pipeline
 * ({@code insurance.document.uploaded/processed/embedded}). Implemented starting FASE 4
 * (Ingestion Pipeline) of the delivery plan in {@code PROJECT_DISCOVERY.md}. Kafka is not
 * used for the synchronous chat flow (brief section 33).
 */
package com.rag.springai.insuranceai.adapters.inbound.kafka;

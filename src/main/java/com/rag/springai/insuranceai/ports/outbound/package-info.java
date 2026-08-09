/**
 * Outbound ports (driven side): repository, vector search, embedding, LLM provider and
 * messaging contracts, implemented by outbound adapters. Populated incrementally as each
 * bounded context is implemented (see {@code PROJECT_DISCOVERY.md} delivery plan):
 * {@link com.rag.springai.insuranceai.ports.outbound.DocumentRepository} (FASE 3),
 * document-processing and messaging ports (FASE 4),
 * {@link com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort},
 * {@link com.rag.springai.insuranceai.ports.outbound.VectorSearchPort},
 * {@link com.rag.springai.insuranceai.ports.outbound.VectorIndexPort} and
 * {@link com.rag.springai.insuranceai.ports.outbound.LlmProvider} (FASE 5).
 */
package com.rag.springai.insuranceai.ports.outbound;

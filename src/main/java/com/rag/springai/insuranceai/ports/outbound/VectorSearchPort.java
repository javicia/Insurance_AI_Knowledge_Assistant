package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.RetrievedChunk;

import java.util.List;

/**
 * Outbound port for the query side of vector search (brief section 3): given a question's
 * embedding, return the chunks most similar to it. Implemented by a Spring AI
 * {@code PgVectorStore}-backed adapter (FASE 5, {@code adapters.outbound.vectorstore}) - never
 * referenced from {@code domain} or {@code application}. Separated from {@link VectorIndexPort}
 * (the write side) so {@code AskInsuranceKnowledgeUseCase} only depends on what it actually
 * needs.
 */
public interface VectorSearchPort {

    /**
     * Returns at most {@code topK} chunks whose similarity to {@code queryEmbedding} is at
     * or above {@code similarityThreshold}, ordered by decreasing similarity. An empty result
     * means no chunk was relevant enough - the caller must not call the LLM in that case
     * (brief section 11).
     */
    List<RetrievedChunk> search(EmbeddingVector queryEmbedding, int topK, double similarityThreshold);
}

package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;

/**
 * Outbound port for the write side of vector search: store a chunk's embedding, keyed by its
 * own {@code DocumentChunkId} so it can later be replaced (re-indexed) or looked back up. See
 * {@code docs/adr/ADR-005-EMBEDDING-AS-DERIVED-PROJECTION.md} for why this is separate from
 * {@code document_chunks} persistence ({@code DocumentChunkRepository}).
 */
public interface VectorIndexPort {

    void index(DocumentChunk chunk, EmbeddingVector embedding, EmbeddingModelDescriptor descriptor);
}

package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;

/**
 * Outbound port for the write side of vector search: store a chunk's embedding, keyed by its
 * own {@code DocumentChunkId} so it can later be replaced (re-indexed) or looked back up. See
 * {@code docs/adr/ADR-005-EMBEDDING-AS-DERIVED-PROJECTION.md} for why this is separate from
 * {@code document_chunks} persistence ({@code DocumentChunkRepository}).
 *
 * <p>{@code documentType}/{@code documentClassification} (FASE 6, brief section 6/7) are an
 * intentional, narrow amendment to ADR-005's original stance of never duplicating Document-level
 * facts into the vector store projection: metadata filtering needs to happen inside the
 * retrieval adapters' own SQL {@code WHERE} clauses (both {@code PgVectorStoreAdapter} and
 * {@code PostgresLexicalSearchAdapter}), not by loading every candidate's parent {@code Document}
 * from {@code DocumentRepository} first and filtering in Java - see
 * {@code docs/adr/ADR-006-ADVANCED-RAG-RETRIEVAL-STRATEGY.md}. Unlike {@code documentName}/{@code
 * versionNumber} (still resolved fresh per FASE 5's citation-building), these two enum values are
 * immutable for the lifetime of a {@code Document}, so denormalizing them carries no staleness
 * risk.
 */
public interface VectorIndexPort {

    void index(DocumentChunk chunk, EmbeddingVector embedding, EmbeddingModelDescriptor descriptor,
            DocumentType documentType, DocumentClassification documentClassification);
}

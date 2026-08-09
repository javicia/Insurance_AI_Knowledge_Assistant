/**
 * RAG bounded context: embedding/vector value objects
 * ({@link com.rag.springai.insuranceai.domain.rag.EmbeddingVector},
 * {@link com.rag.springai.insuranceai.domain.rag.RetrievedChunk}) and the LLM prompt/completion
 * contract ({@link com.rag.springai.insuranceai.domain.rag.LlmPrompt},
 * {@link com.rag.springai.insuranceai.domain.rag.LlmCompletion}) - FASE 5 (Basic RAG).
 *
 * <p>FASE 6 (Advanced RAG) adds the hybrid retrieval model:
 * {@link com.rag.springai.insuranceai.domain.rag.LexicalSearchResult} (PostgreSQL full-text
 * search results), {@link com.rag.springai.insuranceai.domain.rag.RetrievalFilter} (metadata
 * restrictions), {@link com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult} (fused
 * semantic+lexical candidate, optionally reranked) and
 * {@link com.rag.springai.insuranceai.domain.rag.RetrievalDiagnostics}. {@link
 * com.rag.springai.insuranceai.domain.rag.RetrievedChunk} and {@code VectorSearchPort} are
 * unchanged - the semantic branch of the FASE 6 pipeline still produces them.
 */
package com.rag.springai.insuranceai.domain.rag;

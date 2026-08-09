/**
 * Use cases for the Basic RAG flow (FASE 5):
 * {@link com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeUseCase}
 * (question -&gt; grounded answer) and
 * {@link com.rag.springai.insuranceai.application.rag.EmbedDocumentVersionUseCase} (reacts to
 * {@code insurance.document.processed}, embeds and indexes chunks). Hybrid search, reranking
 * and query expansion are FASE 6+.
 */
package com.rag.springai.insuranceai.application.rag;

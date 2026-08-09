/**
 * Use cases for the RAG flow: {@link com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeUseCase}
 * (question -&gt; grounded answer, FASE 5) and
 * {@link com.rag.springai.insuranceai.application.rag.EmbedDocumentVersionUseCase} (reacts to
 * {@code insurance.document.processed}, embeds and indexes chunks, FASE 5).
 *
 * <p>FASE 6 (Advanced RAG) adds {@link com.rag.springai.insuranceai.application.rag.HybridRetrievalService}
 * (pipeline orchestrator, used by {@code AskInsuranceKnowledgeUseCase} instead of calling
 * {@code VectorSearchPort} directly) and its collaborators:
 * {@link com.rag.springai.insuranceai.application.rag.ScoreFusion} (Reciprocal Rank Fusion),
 * {@link com.rag.springai.insuranceai.application.rag.QueryExpander} (deterministic, optional)
 * and {@link com.rag.springai.insuranceai.application.rag.ContextSelector} (dedup/diversity/
 * character-budget). None of them import Spring AI, JDBC or any vendor SDK - only ports and
 * domain types, same as {@code AskInsuranceKnowledgeUseCase} itself.
 */
package com.rag.springai.insuranceai.application.rag;

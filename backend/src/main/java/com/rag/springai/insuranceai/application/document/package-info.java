/**
 * Use cases for document registration, versioning, and ingestion pipeline orchestration:
 * {@link com.rag.springai.insuranceai.application.document.RegisterDocumentUseCase},
 * {@link com.rag.springai.insuranceai.application.document.AddDocumentVersionUseCase} (FASE 3)
 * and {@link com.rag.springai.insuranceai.application.document.ProcessDocumentVersionUseCase}
 * (FASE 4, reacts to {@code insurance.document.uploaded}). Wired to Spring as {@code @Service}
 * beans now that real outbound adapters exist (FASE 4) to inject.
 */
package com.rag.springai.insuranceai.application.document;

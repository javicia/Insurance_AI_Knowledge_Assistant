/**
 * Document Management bounded context. Two aggregates: {@link com.rag.springai.insuranceai.domain.document.Document}
 * (root, owns {@link com.rag.springai.insuranceai.domain.document.DocumentVersion} entities) and
 * {@link com.rag.springai.insuranceai.domain.document.DocumentChunk} (its own root, related to a
 * version by identity only). See
 * {@code docs/adr/ADR-003-DOCUMENT-AGGREGATE-BOUNDARIES.md} for why the boundary is drawn there.
 * Ingestion pipeline behaviour (extraction, chunking, embedding) is implemented starting
 * FASE 4/5 - this package only contains the domain model and its invariants (FASE 3).
 */
package com.rag.springai.insuranceai.domain.document;

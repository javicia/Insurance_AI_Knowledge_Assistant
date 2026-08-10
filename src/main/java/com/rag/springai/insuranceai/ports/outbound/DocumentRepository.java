package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentId;

import java.util.Optional;

/**
 * Outbound port for persisting and retrieving {@link Document} aggregates (brief section 27).
 * A pure domain/application abstraction: no Spring Data, JPA or SQL concept appears here.
 * Implemented by a PostgreSQL adapter starting FASE 4 - see
 * {@code adapters.outbound.persistence}.
 */
public interface DocumentRepository {

    void save(Document document);

    Optional<Document> findById(DocumentId id);

    /**
     * Idempotency lookup (brief section 34,
     * {@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}): finds the document that
     * already owns a version with this exact content, if any, so callers can avoid registering
     * the same bytes twice.
     */
    Optional<Document> findByVersionContentHash(ContentHash contentHash);
}

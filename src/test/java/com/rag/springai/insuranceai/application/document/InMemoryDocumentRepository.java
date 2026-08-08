package com.rag.springai.insuranceai.application.document;

import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only {@link DocumentRepository} stub used to unit test use cases in isolation, without
 * a real persistence adapter. Not part of main sources and therefore not subject to (nor
 * needed by) the hexagonal architecture ArchUnit rules.
 */
final class InMemoryDocumentRepository implements DocumentRepository {

    private final Map<DocumentId, Document> documents = new LinkedHashMap<>();

    @Override
    public void save(Document document) {
        documents.put(document.id(), document);
    }

    @Override
    public Optional<Document> findById(DocumentId id) {
        return Optional.ofNullable(documents.get(id));
    }

    @Override
    public Optional<Document> findByVersionContentHash(ContentHash contentHash) {
        return documents.values().stream()
                .filter(document -> document.versions().stream()
                        .map(DocumentVersion::contentHash)
                        .anyMatch(contentHash::equals))
                .findFirst();
    }
}

package com.rag.springai.insuranceai.application.document;

import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.document.EffectivePeriod;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import com.rag.springai.insuranceai.ports.outbound.DocumentContentStore;
import com.rag.springai.insuranceai.ports.outbound.DocumentEventPublisher;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Registers a document for the first time, starting at version {@code 1.0}: computes its
 * {@link ContentHash}, stores the raw bytes (FASE 4 - extraction happens asynchronously, not
 * here), and publishes {@code insurance.document.uploaded} for the ingestion pipeline to pick
 * up. Does not extract, clean or chunk the content itself.
 *
 * <p>Idempotent for identical content (brief section 34,
 * {@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}): if a version with this exact
 * {@link ContentHash} already exists, the existing {@link Document} is returned unchanged - no
 * new aggregate, stored content, or event is created.
 */
@Service
public final class RegisterDocumentUseCase {

    private final DocumentRepository documentRepository;
    private final DocumentContentStore documentContentStore;
    private final DocumentEventPublisher documentEventPublisher;

    public RegisterDocumentUseCase(DocumentRepository documentRepository, DocumentContentStore documentContentStore,
            DocumentEventPublisher documentEventPublisher) {
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.documentContentStore = Objects.requireNonNull(documentContentStore,
                "documentContentStore must not be null");
        this.documentEventPublisher = Objects.requireNonNull(documentEventPublisher,
                "documentEventPublisher must not be null");
    }

    public Document register(RegisterDocumentCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        ContentHash contentHash = ContentHash.of(command.content());
        Document existing = documentRepository.findByVersionContentHash(contentHash).orElse(null);
        if (existing != null) {
            return existing;
        }

        Document document = Document.register(command.name(), command.type(), command.metadata());
        DocumentVersion initialVersion = DocumentVersion.upload(document.id(), VersionNumber.of(1, 0), contentHash,
                EffectivePeriod.startingAt(command.effectiveFrom()));
        document.addVersion(initialVersion);

        documentRepository.save(document);
        documentContentStore.store(initialVersion.id(), command.content());
        documentEventPublisher.publishDocumentUploaded(document.id(), initialVersion.id(), contentHash);

        return document;
    }
}

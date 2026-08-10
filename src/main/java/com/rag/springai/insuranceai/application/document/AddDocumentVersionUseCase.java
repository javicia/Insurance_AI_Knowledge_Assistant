package com.rag.springai.insuranceai.application.document;

import com.rag.springai.insuranceai.application.document.exception.DocumentNotFoundException;
import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.ports.outbound.DocumentContentStore;
import com.rag.springai.insuranceai.ports.outbound.DocumentEventPublisher;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Adds a new version to an existing document. The overlap and duplicate-version-number checks
 * are enforced by {@link Document#addVersion} itself, not repeated here - this use case only
 * orchestrates loading, delegating, storing the raw content and publishing
 * {@code insurance.document.uploaded}, mirroring {@link RegisterDocumentUseCase}'s idempotency
 * behaviour for identical content.
 */
@Service
public final class AddDocumentVersionUseCase {

    private final DocumentRepository documentRepository;
    private final DocumentContentStore documentContentStore;
    private final DocumentEventPublisher documentEventPublisher;

    public AddDocumentVersionUseCase(DocumentRepository documentRepository,
            DocumentContentStore documentContentStore, DocumentEventPublisher documentEventPublisher) {
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.documentContentStore = Objects.requireNonNull(documentContentStore,
                "documentContentStore must not be null");
        this.documentEventPublisher = Objects.requireNonNull(documentEventPublisher,
                "documentEventPublisher must not be null");
    }

    public DocumentVersion addVersion(AddDocumentVersionCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        ContentHash contentHash = ContentHash.of(command.content());
        Document alreadyRegistered = documentRepository.findByVersionContentHash(contentHash).orElse(null);
        if (alreadyRegistered != null) {
            return alreadyRegistered.versions().stream()
                    .filter(version -> version.contentHash().equals(contentHash))
                    .findFirst()
                    .orElseThrow();
        }

        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));

        DocumentVersion newVersion = DocumentVersion.upload(document.id(), command.versionNumber(), contentHash,
                command.effectivePeriod());
        document.addVersion(newVersion);

        documentRepository.save(document);
        documentContentStore.store(newVersion.id(), command.content());
        documentEventPublisher.publishDocumentUploaded(document.id(), newVersion.id(), contentHash);

        return newVersion;
    }
}

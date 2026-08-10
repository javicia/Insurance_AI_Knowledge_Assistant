package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.application.document.exception.DocumentNotFoundException;
import com.rag.springai.insuranceai.application.document.exception.DocumentVersionNotFoundException;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentStatus;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import com.rag.springai.insuranceai.ports.outbound.DocumentChunkRepository;
import com.rag.springai.insuranceai.ports.outbound.DocumentEventPublisher;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort;
import com.rag.springai.insuranceai.ports.outbound.VectorIndexPort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Reacts to {@code insurance.document.processed}: embeds every {@link DocumentChunk} already
 * persisted for a version (FASE 4) and indexes each vector via {@link VectorIndexPort},
 * completing the pipeline to {@code EMBEDDED} and publishing
 * {@code insurance.document.embedded}.
 *
 * <p>Idempotency follows the same policy as {@code ProcessDocumentVersionUseCase}
 * ({@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}): a duplicate/redelivered
 * event for an already-{@code EMBEDDED} (or {@code FAILED}) version is a no-op; re-embedding a
 * chunk that was already indexed is safe because {@code PgVectorStore.add} upserts by id (see
 * {@code docs/adr/ADR-005-EMBEDDING-AS-DERIVED-PROJECTION.md}).
 */
@Service
public final class EmbedDocumentVersionUseCase {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingModelPort embeddingModelPort;
    private final VectorIndexPort vectorIndexPort;
    private final DocumentEventPublisher documentEventPublisher;

    public EmbedDocumentVersionUseCase(DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository, EmbeddingModelPort embeddingModelPort,
            VectorIndexPort vectorIndexPort, DocumentEventPublisher documentEventPublisher) {
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.documentChunkRepository = Objects.requireNonNull(documentChunkRepository,
                "documentChunkRepository must not be null");
        this.embeddingModelPort = Objects.requireNonNull(embeddingModelPort, "embeddingModelPort must not be null");
        this.vectorIndexPort = Objects.requireNonNull(vectorIndexPort, "vectorIndexPort must not be null");
        this.documentEventPublisher = Objects.requireNonNull(documentEventPublisher,
                "documentEventPublisher must not be null");
    }

    public void embed(EmbedDocumentVersionCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));
        DocumentVersion version = document.version(command.documentVersionId())
                .orElseThrow(() -> new DocumentVersionNotFoundException(command.documentId(),
                        command.documentVersionId()));

        DocumentStatus status = version.status();
        if (status == DocumentStatus.EMBEDDED || status == DocumentStatus.FAILED) {
            return;
        }

        List<DocumentChunk> chunks = documentChunkRepository.findByDocumentVersionId(version.id());

        try {
            if (chunks.isEmpty()) {
                throw new PermanentProcessingException("NO_CHUNKS_TO_EMBED",
                        "Document version " + version.id() + " is PROCESSED but has no chunks to embed");
            }

            EmbeddingModelDescriptor descriptor = embeddingModelPort.descriptor();
            for (DocumentChunk chunk : chunks) {
                EmbeddingVector vector = embeddingModelPort.embed(chunk.content().value());
                vectorIndexPort.index(chunk, vector, descriptor, document.type(), document.metadata().classification());
            }
        }
        catch (PermanentProcessingException e) {
            version.markFailed();
            documentRepository.save(document);
            return;
        }

        version.markEmbedded();
        documentRepository.save(document);

        documentEventPublisher.publishDocumentEmbedded(document.id(), version.id(), chunks.size());
    }
}

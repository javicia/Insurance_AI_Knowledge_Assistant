package com.rag.springai.insuranceai.application.document;

import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import com.rag.springai.insuranceai.application.document.exception.DocumentNotFoundException;
import com.rag.springai.insuranceai.application.document.exception.DocumentVersionNotFoundException;
import com.rag.springai.insuranceai.domain.document.ChunkCandidate;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentStatus;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.document.ExtractedDocument;
import com.rag.springai.insuranceai.ports.outbound.DocumentChunkRepository;
import com.rag.springai.insuranceai.ports.outbound.DocumentChunker;
import com.rag.springai.insuranceai.ports.outbound.DocumentCleaner;
import com.rag.springai.insuranceai.ports.outbound.DocumentContentStore;
import com.rag.springai.insuranceai.ports.outbound.DocumentEventPublisher;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import com.rag.springai.insuranceai.ports.outbound.PdfTextExtractor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Reacts to {@code insurance.document.uploaded} (called by the Kafka inbound adapter, never
 * directly by any other adapter): extracts, cleans and chunks a document version's stored
 * content, persists the resulting {@link DocumentChunk}s, and publishes
 * {@code insurance.document.processed}.
 *
 * <p>Idempotency (brief section 34, {@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}):
 * a duplicate/redelivered event for a version already {@code PROCESSED}/{@code EMBEDDED}/
 * {@code FAILED} is a no-op; one still mid-{@code PROCESSING} (redelivered after a transient
 * failure) is safely re-attempted rather than skipped, because {@link DocumentChunkRepository#replaceAll}
 * has replace, not append, semantics.
 */
@Service
public final class ProcessDocumentVersionUseCase {

    private final DocumentRepository documentRepository;
    private final DocumentContentStore documentContentStore;
    private final DocumentChunkRepository documentChunkRepository;
    private final PdfTextExtractor pdfTextExtractor;
    private final DocumentCleaner documentCleaner;
    private final DocumentChunker documentChunker;
    private final DocumentEventPublisher documentEventPublisher;

    public ProcessDocumentVersionUseCase(DocumentRepository documentRepository,
            DocumentContentStore documentContentStore, DocumentChunkRepository documentChunkRepository,
            PdfTextExtractor pdfTextExtractor, DocumentCleaner documentCleaner, DocumentChunker documentChunker,
            DocumentEventPublisher documentEventPublisher) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.documentContentStore = Objects.requireNonNull(documentContentStore);
        this.documentChunkRepository = Objects.requireNonNull(documentChunkRepository);
        this.pdfTextExtractor = Objects.requireNonNull(pdfTextExtractor);
        this.documentCleaner = Objects.requireNonNull(documentCleaner);
        this.documentChunker = Objects.requireNonNull(documentChunker);
        this.documentEventPublisher = Objects.requireNonNull(documentEventPublisher);
    }

    public void process(ProcessDocumentVersionCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Document document = documentRepository.findById(command.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(command.documentId()));
        DocumentVersion version = document.version(command.documentVersionId())
                .orElseThrow(() -> new DocumentVersionNotFoundException(command.documentId(),
                        command.documentVersionId()));

        DocumentStatus status = version.status();
        if (status == DocumentStatus.PROCESSED || status == DocumentStatus.EMBEDDED
                || status == DocumentStatus.FAILED) {
            return;
        }
        if (status == DocumentStatus.UPLOADED) {
            version.startProcessing();
            documentRepository.save(document);
        }
        // else: already PROCESSING (redelivery after a transient failure) - re-attempt below.

        byte[] content = documentContentStore.retrieve(version.id());

        ExtractedDocument extracted;
        try {
            extracted = pdfTextExtractor.extract(content);
            if (extracted.hasNoExtractableText()) {
                throw new PermanentProcessingException("DOCUMENT_EMPTY_CONTENT",
                        "No extractable text found in document version " + version.id());
            }
        } catch (PermanentProcessingException e) {
            version.markFailed();
            documentRepository.save(document);
            return;
        }

        ExtractedDocument cleaned = documentCleaner.clean(extracted);
        List<ChunkCandidate> candidates = documentChunker.chunk(cleaned);
        List<DocumentChunk> chunks = candidates.stream()
                .map(candidate -> DocumentChunk.create(document.id(), version.id(), candidate.index(),
                        candidate.content(), candidate.metadata()))
                .toList();

        documentChunkRepository.replaceAll(version.id(), chunks);

        version.markProcessed();
        documentRepository.save(document);

        documentEventPublisher.publishDocumentProcessed(document.id(), version.id(), chunks.size());
    }
}

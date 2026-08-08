package com.rag.springai.insuranceai.application.document;

import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.ports.outbound.DocumentEventPublisher;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-only {@link DocumentEventPublisher} stub that records published events for assertions.
 */
final class RecordingDocumentEventPublisher implements DocumentEventPublisher {

    record UploadedEvent(DocumentId documentId, DocumentVersionId documentVersionId, ContentHash contentHash) {
    }

    record ProcessedEvent(DocumentId documentId, DocumentVersionId documentVersionId, int chunkCount) {
    }

    final List<UploadedEvent> uploadedEvents = new ArrayList<>();
    final List<ProcessedEvent> processedEvents = new ArrayList<>();

    @Override
    public void publishDocumentUploaded(DocumentId documentId, DocumentVersionId documentVersionId,
            ContentHash contentHash) {
        uploadedEvents.add(new UploadedEvent(documentId, documentVersionId, contentHash));
    }

    @Override
    public void publishDocumentProcessed(DocumentId documentId, DocumentVersionId documentVersionId,
            int chunkCount) {
        processedEvents.add(new ProcessedEvent(documentId, documentVersionId, chunkCount));
    }
}

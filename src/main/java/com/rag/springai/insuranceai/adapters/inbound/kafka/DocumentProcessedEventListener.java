package com.rag.springai.insuranceai.adapters.inbound.kafka;

import com.rag.springai.insuranceai.adapters.shared.messaging.DocumentProcessedEvent;
import com.rag.springai.insuranceai.adapters.shared.messaging.DocumentTopics;
import com.rag.springai.insuranceai.application.rag.EmbedDocumentVersionCommand;
import com.rag.springai.insuranceai.application.rag.EmbedDocumentVersionUseCase;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter for {@code insurance.document.processed} (brief section 33, FASE 5):
 * triggers embedding and vector indexing of the chunks FASE 4 already persisted. Same
 * retry/error-handling policy as {@link DocumentUploadedEventListener}
 * ({@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}).
 */
@Component
public class DocumentProcessedEventListener {

    private final EmbedDocumentVersionUseCase embedDocumentVersionUseCase;

    public DocumentProcessedEventListener(EmbedDocumentVersionUseCase embedDocumentVersionUseCase) {
        this.embedDocumentVersionUseCase = embedDocumentVersionUseCase;
    }

    @KafkaListener(topics = DocumentTopics.DOCUMENT_PROCESSED, groupId = "insurance-ai-document-processor")
    public void onDocumentProcessed(DocumentProcessedEvent event) {
        embedDocumentVersionUseCase.embed(new EmbedDocumentVersionCommand(DocumentId.of(event.documentId()),
                DocumentVersionId.of(event.documentVersionId())));
    }
}

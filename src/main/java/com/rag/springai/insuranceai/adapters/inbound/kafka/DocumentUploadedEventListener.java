package com.rag.springai.insuranceai.adapters.inbound.kafka;

import com.rag.springai.insuranceai.adapters.shared.messaging.DocumentTopics;
import com.rag.springai.insuranceai.adapters.shared.messaging.DocumentUploadedEvent;
import com.rag.springai.insuranceai.application.document.ProcessDocumentVersionCommand;
import com.rag.springai.insuranceai.application.document.ProcessDocumentVersionUseCase;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter for {@code insurance.document.uploaded} (brief section 33). Any
 * technical failure the use case does not itself catch (see
 * {@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}) propagates out of this
 * listener uncaught, letting the configured {@code DefaultErrorHandler}
 * (see {@code infrastructure.configuration.KafkaErrorHandlingConfiguration}) retry with backoff
 * and eventually route to a dead-letter topic.
 */
@Component
public class DocumentUploadedEventListener {

    private final ProcessDocumentVersionUseCase processDocumentVersionUseCase;

    public DocumentUploadedEventListener(ProcessDocumentVersionUseCase processDocumentVersionUseCase) {
        this.processDocumentVersionUseCase = processDocumentVersionUseCase;
    }

    @KafkaListener(topics = DocumentTopics.DOCUMENT_UPLOADED, groupId = "insurance-ai-document-processor")
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        processDocumentVersionUseCase.process(new ProcessDocumentVersionCommand(
                DocumentId.of(event.documentId()), DocumentVersionId.of(event.documentVersionId())));
    }
}

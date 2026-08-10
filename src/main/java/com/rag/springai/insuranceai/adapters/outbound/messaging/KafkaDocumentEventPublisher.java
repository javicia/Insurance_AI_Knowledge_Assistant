package com.rag.springai.insuranceai.adapters.outbound.messaging;

import com.rag.springai.insuranceai.adapters.shared.messaging.DocumentEmbeddedEvent;
import com.rag.springai.insuranceai.adapters.shared.messaging.DocumentProcessedEvent;
import com.rag.springai.insuranceai.adapters.shared.messaging.DocumentTopics;
import com.rag.springai.insuranceai.adapters.shared.messaging.DocumentUploadedEvent;
import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.ports.outbound.DocumentEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka producer implementation of {@link DocumentEventPublisher}. Keyed by
 * {@code documentVersionId} so all events for the same version land on the same partition and
 * are consumed in order.
 */
@Component
public class KafkaDocumentEventPublisher implements DocumentEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaDocumentEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishDocumentUploaded(DocumentId documentId, DocumentVersionId documentVersionId,
            ContentHash contentHash) {
        kafkaTemplate.send(DocumentTopics.DOCUMENT_UPLOADED, documentVersionId.toString(),
                new DocumentUploadedEvent(documentId.toString(), documentVersionId.toString(), contentHash.value()));
    }

    @Override
    public void publishDocumentProcessed(DocumentId documentId, DocumentVersionId documentVersionId,
            int chunkCount) {
        kafkaTemplate.send(DocumentTopics.DOCUMENT_PROCESSED, documentVersionId.toString(),
                new DocumentProcessedEvent(documentId.toString(), documentVersionId.toString(), chunkCount));
    }

    @Override
    public void publishDocumentEmbedded(DocumentId documentId, DocumentVersionId documentVersionId,
            int chunkCount) {
        kafkaTemplate.send(DocumentTopics.DOCUMENT_EMBEDDED, documentVersionId.toString(),
                new DocumentEmbeddedEvent(documentId.toString(), documentVersionId.toString(), chunkCount));
    }
}

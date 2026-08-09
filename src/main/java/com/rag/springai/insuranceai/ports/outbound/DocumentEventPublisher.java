package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;

/**
 * Outbound port for the document-ingestion event pipeline (brief section 33):
 * {@code insurance.document.uploaded}, {@code insurance.document.processed} and, since
 * FASE 5, {@code insurance.document.embedded}.
 */
public interface DocumentEventPublisher {

    void publishDocumentUploaded(DocumentId documentId, DocumentVersionId documentVersionId, ContentHash contentHash);

    void publishDocumentProcessed(DocumentId documentId, DocumentVersionId documentVersionId, int chunkCount);

    void publishDocumentEmbedded(DocumentId documentId, DocumentVersionId documentVersionId, int chunkCount);
}

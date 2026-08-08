package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;

/**
 * Outbound port for the document-ingestion event pipeline (brief section 33):
 * {@code insurance.document.uploaded} and {@code insurance.document.processed}. There is
 * deliberately no {@code publishDocumentEmbedded} method yet - embeddings are FASE 5, and
 * adding it now would advertise a capability that does not exist (brief section 5: honest
 * architecture).
 */
public interface DocumentEventPublisher {

    void publishDocumentUploaded(DocumentId documentId, DocumentVersionId documentVersionId, ContentHash contentHash);

    void publishDocumentProcessed(DocumentId documentId, DocumentVersionId documentVersionId, int chunkCount);
}

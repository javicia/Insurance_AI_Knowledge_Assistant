package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.document.DocumentVersionId;

/**
 * Outbound port for the raw bytes originally uploaded for a {@code DocumentVersion}. Kept
 * separate from {@link DocumentRepository}: raw content is a storage concern the domain model
 * never represents (neither {@code Document} nor {@code DocumentVersion} carries a byte[]
 * field) - only the Kafka consumer that performs extraction needs to read it back.
 */
public interface DocumentContentStore {

    void store(DocumentVersionId documentVersionId, byte[] content);

    byte[] retrieve(DocumentVersionId documentVersionId);
}

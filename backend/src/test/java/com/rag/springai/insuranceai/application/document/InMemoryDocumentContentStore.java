package com.rag.springai.insuranceai.application.document;

import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.ports.outbound.DocumentContentStore;

import java.util.HashMap;
import java.util.Map;

/**
 * Test-only {@link DocumentContentStore} stub.
 */
final class InMemoryDocumentContentStore implements DocumentContentStore {

    private final Map<DocumentVersionId, byte[]> contents = new HashMap<>();

    @Override
    public void store(DocumentVersionId documentVersionId, byte[] content) {
        contents.put(documentVersionId, content);
    }

    @Override
    public byte[] retrieve(DocumentVersionId documentVersionId) {
        return contents.get(documentVersionId);
    }
}

package com.rag.springai.insuranceai.domain.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DocumentChunkTest {

    @Test
    void createsAChunkRelatedToItsVersionAndDocumentByIdOnly() {
        DocumentId documentId = DocumentId.generate();
        DocumentVersionId versionId = DocumentVersionId.generate();

        DocumentChunk chunk = DocumentChunk.create(documentId, versionId, new ChunkIndex(0),
                new ChunkContent("Water damage is covered up to the policy limit."), ChunkMetadata.empty());

        // DocumentChunk exposes only the identifiers of its owning version/document (see its
        // Javadoc) - the types below are DocumentVersionId/DocumentId, never DocumentVersion
        // or Document themselves, which is what keeps the chunk independently loadable.
        assertEquals(documentId, chunk.documentId());
        assertEquals(versionId, chunk.documentVersionId());
        assertNotNull(chunk.id());
    }

    @Test
    void twoChunksWithDifferentIdsAreNotEqual() {
        DocumentId documentId = DocumentId.generate();
        DocumentVersionId versionId = DocumentVersionId.generate();
        ChunkContent content = new ChunkContent("some text");

        DocumentChunk first = DocumentChunk.create(documentId, versionId, new ChunkIndex(0), content,
                ChunkMetadata.empty());
        DocumentChunk second = DocumentChunk.create(documentId, versionId, new ChunkIndex(1), content,
                ChunkMetadata.empty());

        assertFalse(first.equals(second));
    }

    @Test
    void reconstitutionPreservesGivenIdentity() {
        DocumentChunkId id = DocumentChunkId.generate();

        DocumentChunk chunk = DocumentChunk.reconstitute(id, DocumentId.generate(), DocumentVersionId.generate(),
                new ChunkIndex(3), new ChunkContent("text"), ChunkMetadata.empty());

        assertEquals(id, chunk.id());
    }
}

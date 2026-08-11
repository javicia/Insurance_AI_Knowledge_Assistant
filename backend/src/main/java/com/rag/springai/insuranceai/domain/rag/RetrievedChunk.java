package com.rag.springai.insuranceai.domain.rag;

import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunkId;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;

import java.util.Objects;

/**
 * A {@code DocumentChunk} returned by {@code VectorSearchPort}, ranked by similarity to a
 * question. Reuses {@link ChunkMetadata} from the Document Management bounded context rather
 * than duplicating page/chapter/section/paragraph fields - RAG does not own that structural
 * information, it only reads it (see {@code docs/adr/ADR-005-EMBEDDING-AS-DERIVED-PROJECTION.md}).
 */
public record RetrievedChunk(DocumentChunkId chunkId, DocumentId documentId, DocumentVersionId documentVersionId,
        String content, ChunkMetadata metadata, double similarityScore) {

    public RetrievedChunk {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(documentVersionId, "documentVersionId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }
}

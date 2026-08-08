package com.rag.springai.insuranceai.domain.document;

import java.util.Objects;

/**
 * A single retrievable unit of text extracted from a {@code DocumentVersion}, and the unit of
 * work for embedding and vector search starting FASE 5.
 *
 * <p>Modeled as its own aggregate root, deliberately <b>not</b> as part of the
 * {@link Document}/{@link DocumentVersion} aggregate: a version can have thousands of chunks,
 * and each chunk will eventually carry its own embedding and pgvector row, queried and
 * re-indexed independently (a similarity search returns a handful of chunks across
 * potentially many documents - it never starts from "load a Document and walk its children").
 * Forcing chunks into the Document aggregate would mean any operation on a chunk requires
 * loading and locking the entire version's chunk collection, which does not scale and is not
 * required by any actual invariant. See
 * {@code docs/adr/ADR-003-DOCUMENT-AGGREGATE-BOUNDARIES.md}.
 *
 * <p>Relates to its parent version and document purely by identity
 * ({@link DocumentVersionId}, {@link DocumentId}) - never by holding a reference to the actual
 * {@code DocumentVersion} or {@code Document} object, which is what keeps this aggregate
 * independently loadable.
 */
public final class DocumentChunk {

    private final DocumentChunkId id;
    private final DocumentId documentId;
    private final DocumentVersionId documentVersionId;
    private final ChunkIndex index;
    private final ChunkContent content;
    private final ChunkMetadata metadata;

    private DocumentChunk(DocumentChunkId id, DocumentId documentId, DocumentVersionId documentVersionId,
            ChunkIndex index, ChunkContent content, ChunkMetadata metadata) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.documentVersionId = Objects.requireNonNull(documentVersionId, "documentVersionId must not be null");
        this.index = Objects.requireNonNull(index, "index must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    }

    public static DocumentChunk create(DocumentId documentId, DocumentVersionId documentVersionId,
            ChunkIndex index, ChunkContent content, ChunkMetadata metadata) {
        return new DocumentChunk(DocumentChunkId.generate(), documentId, documentVersionId, index, content,
                metadata);
    }

    public static DocumentChunk reconstitute(DocumentChunkId id, DocumentId documentId,
            DocumentVersionId documentVersionId, ChunkIndex index, ChunkContent content, ChunkMetadata metadata) {
        return new DocumentChunk(id, documentId, documentVersionId, index, content, metadata);
    }

    public DocumentChunkId id() {
        return id;
    }

    public DocumentId documentId() {
        return documentId;
    }

    public DocumentVersionId documentVersionId() {
        return documentVersionId;
    }

    public ChunkIndex index() {
        return index;
    }

    public ChunkContent content() {
        return content;
    }

    public ChunkMetadata metadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DocumentChunk other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

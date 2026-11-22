package com.rag.springai.insuranceai.domain.document;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link DocumentChunk} aggregate. Deliberately independent of
 * {@link DocumentVersionId}: chunks are persisted, queried and (starting FASE 5) embedded and
 * vector-indexed one at a time, never as part of loading their parent version.
 */
public record DocumentChunkId(UUID value) {

    public DocumentChunkId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static DocumentChunkId generate() {
        return new DocumentChunkId(UUID.randomUUID());
    }

    public static DocumentChunkId of(String value) {
        return new DocumentChunkId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

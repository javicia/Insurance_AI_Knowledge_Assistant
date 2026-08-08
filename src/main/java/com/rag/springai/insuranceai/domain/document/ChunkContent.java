package com.rag.springai.insuranceai.domain.document;

import java.util.Objects;

/**
 * The extracted text of a single {@link DocumentChunk}. Chunking strategy itself (how text is
 * split) is an adapter concern implemented starting FASE 4; this type only guards the
 * invariant that a chunk's content is never null or blank.
 */
public record ChunkContent(String value) {

    public ChunkContent {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("chunk content must not be blank");
        }
    }
}

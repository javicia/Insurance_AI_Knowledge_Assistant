package com.rag.springai.insuranceai.domain.document;

/**
 * Zero-based ordinal position of a {@link DocumentChunk} within its parent
 * {@code DocumentVersion}'s content.
 */
public record ChunkIndex(int value) {

    public ChunkIndex {
        if (value < 0) {
            throw new IllegalArgumentException("chunk index must not be negative");
        }
    }
}

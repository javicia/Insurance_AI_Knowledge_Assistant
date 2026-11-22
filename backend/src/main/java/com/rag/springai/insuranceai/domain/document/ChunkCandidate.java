package com.rag.springai.insuranceai.domain.document;

import java.util.Objects;

/**
 * A chunk produced by {@code DocumentChunker}, not yet a {@link DocumentChunk}: the chunker
 * has no notion of which {@link Document}/{@link DocumentVersion} it belongs to, so the
 * application layer assigns identity when turning candidates into real
 * {@code DocumentChunk} aggregates.
 */
public record ChunkCandidate(ChunkIndex index, ChunkContent content, ChunkMetadata metadata) {

    public ChunkCandidate {
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }
}

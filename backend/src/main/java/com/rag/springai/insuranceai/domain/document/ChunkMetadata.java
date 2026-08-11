package com.rag.springai.insuranceai.domain.document;

/**
 * Structural location of a {@link DocumentChunk} within the source document (brief section 9):
 * page, chapter, section and paragraph. All fields are nullable since not every document has
 * every level of structure (e.g. a one-page notice has no chapters).
 */
public record ChunkMetadata(Integer page, String chapter, String section, Integer paragraph) {

    public static ChunkMetadata empty() {
        return new ChunkMetadata(null, null, null, null);
    }
}

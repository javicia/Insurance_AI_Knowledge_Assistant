package com.rag.springai.insuranceai.application.rag;

import java.util.Objects;

/**
 * A citation backing an {@link RagAnswer}. Every field is copied from data that already
 * existed on the {@code Document}/{@code DocumentVersion}/{@code DocumentChunk} aggregates
 * that produced the retrieved chunk - nothing here is inferred or invented (brief section 9).
 * Field name {@code document} (rather than {@code documentName}) matches the citation JSON
 * shape specified in brief section 10/22/40 exactly.
 */
public record SourceReference(String documentId, String document, String version, Integer page, String section,
        String chunkId) {

    public SourceReference {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(chunkId, "chunkId must not be null");
    }
}

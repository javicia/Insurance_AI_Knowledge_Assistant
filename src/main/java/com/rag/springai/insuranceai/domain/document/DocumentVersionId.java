package com.rag.springai.insuranceai.domain.document;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link DocumentVersion} entity within its owning {@link Document} aggregate.
 * Referenced by {@link DocumentChunk} instead of a full {@code DocumentVersion} object, so
 * that loading a chunk never requires loading the version (or the document) that produced it.
 */
public record DocumentVersionId(UUID value) {

    public DocumentVersionId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static DocumentVersionId generate() {
        return new DocumentVersionId(UUID.randomUUID());
    }

    public static DocumentVersionId of(String value) {
        return new DocumentVersionId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

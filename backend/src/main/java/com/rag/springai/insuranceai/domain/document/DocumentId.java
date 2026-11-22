package com.rag.springai.insuranceai.domain.document;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link Document} aggregate. Documents are identified independently of any
 * particular version's content.
 */
public record DocumentId(UUID value) {

    public DocumentId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static DocumentId generate() {
        return new DocumentId(UUID.randomUUID());
    }

    public static DocumentId of(String value) {
        return new DocumentId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

package com.rag.springai.insuranceai.domain.rag;

import java.util.List;
import java.util.Objects;

/**
 * A dense vector representation of a piece of text, produced by an embedding model. Wraps
 * {@code List<Float>} rather than {@code float[]} so value equality (used by tests validating
 * embedding dimension/content) works via the generated record methods instead of requiring
 * manual array comparison.
 */
public record EmbeddingVector(List<Float> values) {

    public EmbeddingVector {
        Objects.requireNonNull(values, "values must not be null");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        values = List.copyOf(values);
    }

    public int dimension() {
        return values.size();
    }
}

package com.rag.springai.insuranceai.domain.rag;

import java.util.Objects;

/**
 * Identifies exactly which embedding model produced (or should produce) a vector: provider,
 * model name, model version and dimension count, plus the similarity metric used to compare
 * vectors it produces. Recorded alongside every stored vector (brief section 5) so a future
 * model change can be detected by comparing a stored descriptor against the currently
 * configured one - see {@code docs/adr/ADR-005-EMBEDDING-AS-DERIVED-PROJECTION.md}.
 */
public record EmbeddingModelDescriptor(String provider, String model, String modelVersion, int dimensions,
        SimilarityMetric similarityMetric) {

    public EmbeddingModelDescriptor {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(similarityMetric, "similarityMetric must not be null");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
    }
}

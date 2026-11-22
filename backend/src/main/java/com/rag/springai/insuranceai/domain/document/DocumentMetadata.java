package com.rag.springai.insuranceai.domain.document;

import java.util.Objects;

/**
 * Document-level descriptive metadata (brief section 9). {@code product}, {@code country} and
 * {@code language} may be {@code null} for documents not tied to a specific insurance product
 * (e.g. a corporate privacy policy) or jurisdiction.
 */
public record DocumentMetadata(String product, String country, String language,
        DocumentClassification classification, String source) {

    public DocumentMetadata {
        Objects.requireNonNull(classification, "classification must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
    }
}

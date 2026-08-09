package com.rag.springai.insuranceai.domain.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingModelDescriptorTest {

    @Test
    void rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingModelDescriptor("openai", "text-embedding-3-small", null, 0,
                        SimilarityMetric.COSINE));
    }

    @Test
    void rejectsANullProvider() {
        assertThrows(NullPointerException.class,
                () -> new EmbeddingModelDescriptor(null, "text-embedding-3-small", null, 1536,
                        SimilarityMetric.COSINE));
    }

    @Test
    void allowsANullModelVersionSinceNotAllProvidersExposeOne() {
        new EmbeddingModelDescriptor("openai", "text-embedding-3-small", null, 1536, SimilarityMetric.COSINE);
    }
}

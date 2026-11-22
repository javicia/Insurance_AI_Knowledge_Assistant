package com.rag.springai.insuranceai.domain.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingVectorTest {

    @Test
    void dimensionMatchesTheNumberOfValues() {
        EmbeddingVector vector = new EmbeddingVector(List.of(0.1f, 0.2f, 0.3f));

        assertEquals(3, vector.dimension());
    }

    @Test
    void rejectsAnEmptyVector() {
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingVector(List.of()));
    }

    @Test
    void rejectsANullVector() {
        assertThrows(NullPointerException.class, () -> new EmbeddingVector(null));
    }

    @Test
    void twoVectorsWithEqualValuesAreEqual() {
        assertEquals(new EmbeddingVector(List.of(1f, 2f)), new EmbeddingVector(List.of(1f, 2f)));
    }
}

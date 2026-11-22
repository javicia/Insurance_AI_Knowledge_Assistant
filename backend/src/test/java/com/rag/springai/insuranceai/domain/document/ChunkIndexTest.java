package com.rag.springai.insuranceai.domain.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkIndexTest {

    @Test
    void acceptsZeroAndPositiveValues() {
        assertEquals(0, new ChunkIndex(0).value());
        assertEquals(42, new ChunkIndex(42).value());
    }

    @Test
    void rejectsANegativeValue() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkIndex(-1));
    }
}

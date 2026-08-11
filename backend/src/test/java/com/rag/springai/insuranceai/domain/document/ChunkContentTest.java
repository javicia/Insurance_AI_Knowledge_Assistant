package com.rag.springai.insuranceai.domain.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkContentTest {

    @Test
    void acceptsNonBlankText() {
        assertEquals("Water damage coverage", new ChunkContent("Water damage coverage").value());
    }

    @Test
    void rejectsBlankText() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkContent("   "));
    }

    @Test
    void rejectsNullText() {
        assertThrows(NullPointerException.class, () -> new ChunkContent(null));
    }
}

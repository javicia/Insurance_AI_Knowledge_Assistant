package com.rag.springai.insuranceai.domain.document;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentChunkIdTest {

    @Test
    void generateProducesUniqueIds() {
        assertNotEquals(DocumentChunkId.generate(), DocumentChunkId.generate());
    }

    @Test
    void ofParsesAValidUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new DocumentChunkId(uuid), DocumentChunkId.of(uuid.toString()));
    }

    @Test
    void rejectsAMalformedUuidString() {
        assertThrows(IllegalArgumentException.class, () -> DocumentChunkId.of("not-a-uuid"));
    }
}

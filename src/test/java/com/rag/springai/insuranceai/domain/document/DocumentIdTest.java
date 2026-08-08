package com.rag.springai.insuranceai.domain.document;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentIdTest {

    @Test
    void generateProducesUniqueIds() {
        assertNotEquals(DocumentId.generate(), DocumentId.generate());
    }

    @Test
    void ofParsesAValidUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new DocumentId(uuid), DocumentId.of(uuid.toString()));
    }

    @Test
    void rejectsAMalformedUuidString() {
        assertThrows(IllegalArgumentException.class, () -> DocumentId.of("not-a-uuid"));
    }

    @Test
    void rejectsANullValue() {
        assertThrows(NullPointerException.class, () -> new DocumentId(null));
    }
}

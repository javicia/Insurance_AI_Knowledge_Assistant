package com.rag.springai.insuranceai.domain.document;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentVersionIdTest {

    @Test
    void generateProducesUniqueIds() {
        assertNotEquals(DocumentVersionId.generate(), DocumentVersionId.generate());
    }

    @Test
    void ofParsesAValidUuidString() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new DocumentVersionId(uuid), DocumentVersionId.of(uuid.toString()));
    }

    @Test
    void rejectsAMalformedUuidString() {
        assertThrows(IllegalArgumentException.class, () -> DocumentVersionId.of("not-a-uuid"));
    }
}

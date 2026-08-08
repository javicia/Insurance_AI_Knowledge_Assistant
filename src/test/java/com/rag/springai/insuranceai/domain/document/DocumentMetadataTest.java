package com.rag.springai.insuranceai.domain.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class DocumentMetadataTest {

    @Test
    void allowsProductCountryAndLanguageToBeNull() {
        assertDoesNotThrow(
                () -> new DocumentMetadata(null, null, null, DocumentClassification.INTERNAL, "privacy-policy.pdf"));
    }

    @Test
    void rejectsANullClassification() {
        assertThrows(NullPointerException.class, () -> new DocumentMetadata("home", "ES", "en", null, "source.pdf"));
    }

    @Test
    void rejectsABlankSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentMetadata("home", "ES", "en", DocumentClassification.INTERNAL, "   "));
    }
}

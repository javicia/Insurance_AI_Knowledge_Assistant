package com.rag.springai.insuranceai.domain.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TraceIdTest {

    @Test
    void generateProducesNonBlankUniqueValues() {
        TraceId first = TraceId.generate();
        TraceId second = TraceId.generate();

        assertFalse(first.value().isBlank());
        assertNotEquals(first, second);
    }

    @Test
    void ofWrapsAGivenValue() {
        TraceId traceId = TraceId.of("abc-123");

        assertEquals("abc-123", traceId.value());
    }

    @Test
    void rejectsBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> TraceId.of("   "));
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> TraceId.of(null));
    }
}

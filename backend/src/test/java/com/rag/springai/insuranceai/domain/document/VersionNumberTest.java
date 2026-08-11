package com.rag.springai.insuranceai.domain.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionNumberTest {

    @Test
    void parsesAMajorMinorString() {
        VersionNumber versionNumber = VersionNumber.parse("3.2");

        assertEquals(new VersionNumber(3, 2), versionNumber);
    }

    @Test
    void parsesAStringWithALeadingVPrefix() {
        VersionNumber versionNumber = VersionNumber.parse("v1.0");

        assertEquals(new VersionNumber(1, 0), versionNumber);
    }

    @Test
    void rendersAsMajorDotMinor() {
        assertEquals("3.2", VersionNumber.of(3, 2).toString());
    }

    @Test
    void rejectsAMalformedString() {
        assertThrows(IllegalArgumentException.class, () -> VersionNumber.parse("not-a-version"));
    }

    @Test
    void rejectsNegativeComponents() {
        assertThrows(IllegalArgumentException.class, () -> VersionNumber.of(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> VersionNumber.of(0, -1));
    }

    @Test
    void ordersByMajorThenMinor() {
        assertTrue(VersionNumber.of(1, 0).compareTo(VersionNumber.of(2, 0)) < 0);
        assertTrue(VersionNumber.of(2, 0).compareTo(VersionNumber.of(1, 9)) > 0);
        assertTrue(VersionNumber.of(3, 2).compareTo(VersionNumber.of(3, 2)) == 0);
    }
}

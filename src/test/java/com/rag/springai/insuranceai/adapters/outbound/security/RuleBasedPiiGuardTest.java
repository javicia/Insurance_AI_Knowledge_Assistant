package com.rag.springai.insuranceai.adapters.outbound.security;

import com.rag.springai.insuranceai.domain.security.PiiAssessment;
import com.rag.springai.insuranceai.domain.security.PiiType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedPiiGuardTest {

    private final RuleBasedPiiGuard guard = new RuleBasedPiiGuard();

    @Test
    void detectsEmail() {
        PiiAssessment assessment = guard.scan("Contact me at jane.doe@example.com for details.");

        assertTrue(assessment.detected());
        assertEquals(PiiType.EMAIL, assessment.matches().get(0).type());
    }

    @Test
    void detectsSpanishIban() {
        assertTrue(guard.scan("Please refund to ES91 2100 0418 4502 0005 1332.").detected());
    }

    @Test
    void detectsSpanishNationalId() {
        assertTrue(guard.scan("My DNI is 12345678Z.").detected());
        assertTrue(guard.scan("My NIE is X1234567L.").detected());
    }

    @Test
    void detectsSpanishPhoneNumber() {
        assertTrue(guard.scan("Call me at 612345678.").detected());
    }

    @Test
    void neverReturnsTheRawMatchedValue() {
        PiiAssessment assessment = guard.scan("Contact me at jane.doe@example.com for details.");

        String masked = assessment.matches().get(0).maskedValue();
        assertFalse(masked.contains("jane.doe@example.com"));
        assertTrue(masked.startsWith("j***@"));
    }

    @Test
    void redactReplacesDetectedPiiWithAPlaceholder() {
        String redacted = guard.redact("Contact me at jane.doe@example.com please.");

        assertFalse(redacted.contains("jane.doe@example.com"));
        assertTrue(redacted.contains("[REDACTED:EMAIL]"));
    }

    @Test
    void textWithoutPiiIsClean() {
        assertFalse(guard.scan("What does my policy cover for water damage?").detected());
    }
}

package com.rag.springai.insuranceai.adapters.outbound.security;

import com.rag.springai.insuranceai.domain.security.PiiAssessment;
import com.rag.springai.insuranceai.domain.security.PiiMatch;
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

    // --- FASE 24: CREDIT_CARD (Luhn-validated), API_KEY, JWT, CREDENTIAL ---

    @Test
    void detectsALuhnValidCreditCardNumber() {
        // 4111111111111111 is the well-known publicly-documented Visa test number (Luhn-valid,
        // never a real issued card) - safe to hardcode in a test.
        PiiAssessment assessment = guard.scan("Card number: 4111111111111111 expires 12/29.");

        assertTrue(assessment.detected());
        assertTrue(assessment.matches().stream().anyMatch(m -> m.type() == PiiType.CREDIT_CARD));
    }

    @Test
    void doesNotFlagADigitRunThatFailsTheLuhnChecksum() {
        // Same length as the test card above, last digit changed so the checksum no longer holds
        // - proves the detector is not just "any 13-19 digit run", cutting false positives on
        // arbitrary long numbers (invoice/policy numbers, phone extensions).
        PiiAssessment assessment = guard.scan("Reference number: 4111111111111112 for this ticket.");

        assertFalse(assessment.matches().stream().anyMatch(m -> m.type() == PiiType.CREDIT_CARD));
    }

    @Test
    void detectsCreditCardNumberWithSpacesOrDashes() {
        assertTrue(guard.scan("4111 1111 1111 1111").detected());
        assertTrue(guard.scan("4111-1111-1111-1111").detected());
    }

    @Test
    void detectsCommonApiKeyShapes() {
        assertEquals(PiiType.API_KEY, guard.scan("AWS key: AKIAIOSFODNN7EXAMPLE").matches().get(0).type());
        assertEquals(PiiType.API_KEY,
                guard.scan("token ghp_abcdefghijklmnopqrstuvwxyz0123456789").matches().get(0).type());
        assertEquals(PiiType.API_KEY,
                guard.scan("key sk-abcdefghijklmnopqrstuvwxyz0123456789").matches().get(0).type());
        assertEquals(PiiType.API_KEY, guard.scan("slack token xoxb-1234567890-abcdefghij").matches().get(0).type());
    }

    @Test
    void detectsAJwtShapedToken() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";

        PiiAssessment assessment = guard.scan("Authorization: Bearer " + jwt);

        assertTrue(assessment.matches().stream().anyMatch(m -> m.type() == PiiType.JWT));
    }

    @Test
    void detectsInlinePasswordAndSecretAssignments() {
        assertTrue(guard.scan("password: hunter2").detected());
        assertTrue(guard.scan("secret=abc123XYZ").detected());
        assertTrue(guard.scan("api_key: sk-not-a-real-key").detected());
    }

    @Test
    void secretTypesAreMaskedEntirelyNotJustPartially() {
        PiiMatch match = guard.scan("password: hunter2").matches().get(0);

        assertFalse(match.maskedValue().contains("hunter2"));
    }
}

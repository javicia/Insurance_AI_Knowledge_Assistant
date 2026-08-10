package com.rag.springai.insuranceai.application.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code blocked} is the structured discriminator a caller needs to distinguish "no relevant
 * evidence" from "blocked by the prompt-injection guardrail" - both otherwise produce an
 * identical {@code grounding.status == NOT_GROUNDED} (brief FASE 15 frontend integration).
 */
class RagAnswerTest {

    @Test
    void noAnswerIsNotBlocked() {
        RagAnswer answer = RagAnswer.noAnswer("trace-1");

        assertFalse(answer.blocked());
        assertEquals(GroundingStatus.NOT_GROUNDED, answer.grounding().status());
    }

    @Test
    void blockedIsMarkedBlocked() {
        RagAnswer answer = RagAnswer.blocked("trace-1");

        assertTrue(answer.blocked());
        assertEquals(GroundingStatus.NOT_GROUNDED, answer.grounding().status());
    }
}

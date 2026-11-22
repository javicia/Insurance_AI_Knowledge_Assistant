package com.rag.springai.insuranceai.domain.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FASE 27: token usage is optional, and "absent" is never silently turned into "zero". */
class LlmCompletionTest {

    @Test
    void acceptsACompletionWithNoTokenUsageAtAll() {
        LlmCompletion completion = new LlmCompletion("answer");

        assertEquals("answer", completion.text());
        assertNull(completion.inputTokens());
        assertNull(completion.outputTokens());
        assertFalse(completion.hasTokenUsage());
    }

    @Test
    void carriesTokenUsageWhenTheProviderReportedIt() {
        LlmCompletion completion = new LlmCompletion("answer", 120, 34);

        assertTrue(completion.hasTokenUsage());
        assertEquals(120, completion.inputTokens());
        assertEquals(34, completion.outputTokens());
        assertEquals(154, completion.totalTokens());
    }

    @Test
    void readingATotalWithoutTokenUsageFailsInsteadOfReturningZero() {
        assertThrows(IllegalStateException.class, () -> new LlmCompletion("answer").totalTokens());
    }

    @Test
    void halfReportedUsageCountsAsNoUsage() {
        // Neither a request cost nor a total is computable from one side alone, so treating this
        // as "partial usage" would only produce a number that looks complete but is not.
        assertFalse(new LlmCompletion("answer", 120, null).hasTokenUsage());
        assertFalse(new LlmCompletion("answer", null, 34).hasTokenUsage());
    }

    @Test
    void zeroTokensIsAValidReportAndIsNotConfusedWithAbsentUsage() {
        LlmCompletion completion = new LlmCompletion("", 0, 0);

        assertTrue(completion.hasTokenUsage());
        assertEquals(0, completion.totalTokens());
    }

    @Test
    void rejectsNegativeTokenCounts() {
        assertThrows(IllegalArgumentException.class, () -> new LlmCompletion("answer", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LlmCompletion("answer", 0, -1));
    }

    @Test
    void stillRejectsNullText() {
        assertThrows(NullPointerException.class, () -> new LlmCompletion(null));
    }
}

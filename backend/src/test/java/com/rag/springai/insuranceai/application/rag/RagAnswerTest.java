package com.rag.springai.insuranceai.application.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code answerMode} is the structured discriminator a caller needs to tell the three
 * non-grounded states apart - no relevant evidence, blocked by the prompt-injection guardrail,
 * and answered from general AI knowledge - all of which otherwise produce an identical
 * {@code grounding.status == NOT_GROUNDED} (brief FASE 15 frontend integration, extended in
 * FASE 28).
 */
class RagAnswerTest {

    @Test
    void noAnswerIsReportedAsNoAnswer() {
        RagAnswer answer = RagAnswer.noAnswer("trace-1");

        assertEquals(AnswerMode.NO_ANSWER, answer.answerMode());
        assertEquals(GroundingStatus.NOT_GROUNDED, answer.grounding().status());
        assertTrue(answer.sources().isEmpty());
    }

    @Test
    void blockedIsReportedAsBlocked() {
        RagAnswer answer = RagAnswer.blocked("trace-1");

        assertEquals(AnswerMode.BLOCKED, answer.answerMode());
        assertEquals(GroundingStatus.NOT_GROUNDED, answer.grounding().status());
        assertTrue(answer.sources().isEmpty());
    }

    @Test
    void aGeneralAiAnswerIsNotGroundedAndCarriesNoSources() {
        RagAnswer answer = RagAnswer.generalAi("Una franquicia es la parte del siniestro que asume el asegurado.",
                "trace-1", false);

        assertEquals(AnswerMode.GENERAL_AI_UNVERIFIED, answer.answerMode());
        assertEquals(GroundingStatus.NOT_GROUNDED, answer.grounding().status(),
                "a general-AI answer is genuinely not grounded - reporting otherwise would be a false claim");
        assertTrue(answer.sources().isEmpty());
    }

    @Test
    void anUngroundedAnswerCannotCarrySources() {
        // The invariant that makes "never invent citations" structural rather than a convention:
        // a caller cannot construct a GENERAL_AI_UNVERIFIED answer that cites the insufficient
        // evidence which triggered the fallback, even by mistake.
        List<SourceReference> sources = List.of(
                new SourceReference("doc-1", "Póliza de Auto", "1.0", 3, "Coberturas", "chunk-1"));

        assertThrows(IllegalArgumentException.class, () -> new RagAnswer("respuesta general", sources,
                new Grounding(GroundingStatus.NOT_GROUNDED), AnswerMode.GENERAL_AI_UNVERIFIED, "trace-1", false));
    }
}

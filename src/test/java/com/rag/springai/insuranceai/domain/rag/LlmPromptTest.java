package com.rag.springai.insuranceai.domain.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmPromptTest {

    @Test
    void keepsSystemInstructionsAndContextSeparate() {
        LlmPrompt prompt = new LlmPrompt("system text", "What is covered?", List.of("passage one", "passage two"));

        assertEquals("system text", prompt.systemInstructions());
        assertEquals("What is covered?", prompt.userQuestion());
        assertEquals(List.of("passage one", "passage two"), prompt.retrievedContextPassages());
    }

    @Test
    void rejectsABlankQuestion() {
        assertThrows(IllegalArgumentException.class, () -> new LlmPrompt("system", "   ", List.of()));
    }

    @Test
    void rejectsNullSystemInstructions() {
        assertThrows(NullPointerException.class, () -> new LlmPrompt(null, "question", List.of()));
    }
}

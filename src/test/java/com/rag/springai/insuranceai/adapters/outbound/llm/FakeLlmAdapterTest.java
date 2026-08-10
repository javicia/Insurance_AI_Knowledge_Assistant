package com.rag.springai.insuranceai.adapters.outbound.llm;

import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeLlmAdapterTest {

    private final FakeLlmAdapter adapter = new FakeLlmAdapter();

    @Test
    void quotesTheFirstRetrievedPassageAndClearlyLabelsItselfAsNotReal() {
        LlmCompletion completion = adapter
                .complete(new LlmPrompt("system", "question", List.of("water damage passage")));

        assertTrue(completion.text().contains("FAKE PROVIDER"));
        assertTrue(completion.text().contains("water damage passage"));
    }

    @Test
    void handlesNoRetrievedPassagesGracefully() {
        LlmCompletion completion = adapter.complete(new LlmPrompt("system", "question", List.of()));

        assertTrue(completion.text().contains("FAKE PROVIDER"));
    }
}

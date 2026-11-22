package com.rag.springai.insuranceai.adapters.outbound.llm;

import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import com.rag.springai.insuranceai.ports.outbound.LlmProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic, offline {@link LlmProvider}: does not call any vendor API. Returns a fixed
 * template that quotes the first retrieved passage, just enough to let automated tests and
 * manual end-to-end validation exercise the full RAG flow (embedding -&gt; retrieval -&gt;
 * grounding -&gt; citations) without OpenAI/Anthropic credentials (brief section 17/18).
 *
 * <p><b>Never claims to have "validated OpenAI" or "validated Anthropic"</b> - a run using this
 * adapter proves the pipeline mechanics, not that either real provider works. Selected only
 * when {@code insurance-ai.ai.provider: fake}.
 *
 * <p>For the same reason it reports no token usage at all ({@code null}, not {@code 0}): there
 * was no billable call to count, and a fabricated {@code 0} would let a benchmark run against
 * this adapter silently report a cost of zero as if it were a measurement (see
 * {@link LlmCompletion} and {@code LlmCostCalculator}).
 */
@Component
@ConditionalOnProperty(prefix = "insurance-ai.ai", name = "provider", havingValue = "fake")
public class FakeLlmAdapter implements LlmProvider {

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
        String firstPassage = prompt.retrievedContextPassages().isEmpty() ? ""
                : prompt.retrievedContextPassages().get(0);
        String answer = "[FAKE PROVIDER - not a real LLM call] Based on the retrieved documentation: "
                + firstPassage;
        return new LlmCompletion(answer);
    }
}

package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;

/**
 * Outbound port for chat completion (brief section 7). Exactly one implementation is active at
 * a time, selected by {@code insurance-ai.ai.provider}
 * ({@code adapters.outbound.llm.OpenAiLlmAdapter}, {@code AnthropicLlmAdapter} or
 * {@code FakeLlmAdapter}) - the application layer never sees the OpenAI/Anthropic SDKs or
 * Spring AI's {@code ChatModel}/{@code ChatClient}.
 */
public interface LlmProvider {

    LlmCompletion complete(LlmPrompt prompt);
}

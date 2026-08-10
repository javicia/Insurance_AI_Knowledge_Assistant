package com.rag.springai.insuranceai.adapters.outbound.llm;

import com.rag.springai.insuranceai.adapters.shared.llm.LlmMessageFormatter;
import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import com.rag.springai.insuranceai.domain.shared.exception.TransientProcessingException;
import com.rag.springai.insuranceai.ports.outbound.LlmProvider;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * {@link LlmProvider} backed by Spring AI's {@code OpenAiChatModel}. Active only when
 * {@code insurance-ai.ai.provider: openai} (brief section 7/8) - the application layer never
 * sees this class or the OpenAI SDK.
 *
 * <p><b>Failure classification (FASE 11, brief section 55):</b> Spring AI's own auto-configured
 * retry ({@code spring.ai.retry.*}, {@code docs/resilience/RESILIENCE.md}) already retries
 * transient failures (5xx, timeout, connection drop) with exponential backoff before this method
 * ever sees an exception, and - by its {@code on-client-errors: false} default - never retries a
 * 4xx. A 4xx that still reaches here ({@link HttpClientErrorException}: invalid API key, malformed
 * request, rate limit exhausted) is therefore genuinely not retry-safe, unlike everything else,
 * which either was already retried by Spring AI or is a fresh transient condition a caller-level
 * retry could plausibly still fix.
 */
@Component
@ConditionalOnProperty(prefix = "insurance-ai.ai", name = "provider", havingValue = "openai")
public class OpenAiLlmAdapter implements LlmProvider {

    private final OpenAiChatModel chatModel;

    public OpenAiLlmAdapter(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public LlmCompletion complete(LlmPrompt prompt) {
        try {
            String userMessage = LlmMessageFormatter.userMessage(prompt.userQuestion(),
                    prompt.retrievedContextPassages());
            String response = chatModel.call(new SystemMessage(prompt.systemInstructions()),
                    new UserMessage(userMessage));
            return new LlmCompletion(response);
        }
        catch (HttpClientErrorException e) {
            throw new PermanentProcessingException("OPENAI_CHAT_COMPLETION_REJECTED",
                    "OpenAI rejected the chat completion request (" + e.getStatusCode() + ") - not retry-safe", e);
        }
        catch (RuntimeException e) {
            throw new TransientProcessingException("OPENAI_CHAT_COMPLETION_FAILED",
                    "OpenAI chat completion failed", e);
        }
    }
}

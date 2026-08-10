package com.rag.springai.insuranceai.adapters.outbound.llm;

import com.rag.springai.insuranceai.adapters.shared.llm.LlmFailureClassifier;
import com.rag.springai.insuranceai.adapters.shared.llm.LlmMessageFormatter;
import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import com.rag.springai.insuranceai.domain.shared.exception.TransientProcessingException;
import com.rag.springai.insuranceai.ports.outbound.LlmProvider;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * {@link LlmProvider} backed by Spring AI's {@code AnthropicChatModel}. Active only when
 * {@code insurance-ai.ai.provider: anthropic} (brief section 7/8) - swapping from OpenAI to
 * Anthropic never touches {@code AskInsuranceKnowledgeUseCase}.
 *
 * <p>Failure classification: see {@code OpenAiLlmAdapter}'s Javadoc - identical reasoning
 * (including the FASE 14 429/408-as-transient refinement via {@link LlmFailureClassifier}), same
 * Spring AI auto-configured retry (FASE 11, {@code docs/resilience/RESILIENCE.md}).
 */
@Component
@ConditionalOnProperty(prefix = "insurance-ai.ai", name = "provider", havingValue = "anthropic")
public class AnthropicLlmAdapter implements LlmProvider {

    private final AnthropicChatModel chatModel;

    public AnthropicLlmAdapter(AnthropicChatModel chatModel) {
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
            if (LlmFailureClassifier.isTransientHttpStatus(e.getStatusCode())) {
                throw new TransientProcessingException("ANTHROPIC_CHAT_COMPLETION_RATE_LIMITED",
                        "Anthropic chat completion request was rate-limited/timed out (" + e.getStatusCode()
                                + ") - transient, retry-safe",
                        e);
            }
            throw new PermanentProcessingException("ANTHROPIC_CHAT_COMPLETION_REJECTED",
                    "Anthropic rejected the chat completion request (" + e.getStatusCode() + ") - not retry-safe", e);
        }
        catch (RuntimeException e) {
            throw new TransientProcessingException("ANTHROPIC_CHAT_COMPLETION_FAILED",
                    "Anthropic chat completion failed", e);
        }
    }
}

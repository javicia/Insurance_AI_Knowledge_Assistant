package com.rag.springai.insuranceai.adapters.outbound.llm;

import com.rag.springai.insuranceai.adapters.shared.llm.LlmMessageFormatter;
import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import com.rag.springai.insuranceai.domain.shared.exception.TransientProcessingException;
import com.rag.springai.insuranceai.ports.outbound.LlmProvider;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link LlmProvider} backed by Spring AI's {@code AnthropicChatModel}. Active only when
 * {@code insurance-ai.ai.provider: anthropic} (brief section 7/8) - swapping from OpenAI to
 * Anthropic never touches {@code AskInsuranceKnowledgeUseCase}.
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
        catch (RuntimeException e) {
            throw new TransientProcessingException("ANTHROPIC_CHAT_COMPLETION_FAILED",
                    "Anthropic chat completion failed", e);
        }
    }
}

package com.rag.springai.insuranceai.adapters.outbound.llm;

import com.rag.springai.insuranceai.adapters.shared.llm.ChatResponseMapper;
import com.rag.springai.insuranceai.adapters.shared.llm.LlmFailureClassifier;
import com.rag.springai.insuranceai.adapters.shared.llm.LlmMessageFormatter;
import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import com.rag.springai.insuranceai.domain.shared.exception.TransientProcessingException;
import com.rag.springai.insuranceai.ports.outbound.LlmProvider;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

/**
 * {@link LlmProvider} backed by Spring AI's {@code OpenAiChatModel}. Active only when
 * {@code insurance-ai.ai.provider: openai} (brief section 7/8) - the application layer never
 * sees this class or the OpenAI SDK.
 *
 * <p><b>Failure classification (FASE 11, brief section 55; refined FASE 14 audit remediation -
 * see {@code docs/adr/ADR-012-AUDIT-REMEDIATION.md}):</b> Spring AI's own auto-configured retry
 * ({@code spring.ai.retry.*}, {@code docs/resilience/RESILIENCE.md}) already retries transient
 * failures (5xx, timeout, connection drop) with exponential backoff before this method ever sees
 * an exception, and - by its {@code on-client-errors: false} default - never retries a 4xx. Most
 * 4xx that reach here ({@link HttpClientErrorException}: invalid API key, malformed request) are
 * genuinely not retry-safe - but {@code 429 Too Many Requests} and {@code 408 Request Timeout}
 * are conventionally transient (see {@link LlmFailureClassifier}), so those two are classified as
 * {@link TransientProcessingException} instead, allowing a caller-level retry to plausibly still
 * succeed.
 *
 * <p><b>Token usage (FASE 27, benchmarking):</b> calls the {@code ChatResponse}-returning
 * overload rather than the {@code String} convenience one, because the latter discards the
 * response metadata that carries the real prompt/completion token counts - see
 * {@link ChatResponseMapper}. The extracted text is identical either way.
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
            return ChatResponseMapper.toCompletion(chatModel.call(new Prompt(
                    List.of(new SystemMessage(prompt.systemInstructions()), new UserMessage(userMessage)))));
        }
        catch (HttpClientErrorException e) {
            if (LlmFailureClassifier.isTransientHttpStatus(e.getStatusCode())) {
                throw new TransientProcessingException("OPENAI_CHAT_COMPLETION_RATE_LIMITED",
                        "OpenAI chat completion request was rate-limited/timed out (" + e.getStatusCode()
                                + ") - transient, retry-safe",
                        e);
            }
            throw new PermanentProcessingException("OPENAI_CHAT_COMPLETION_REJECTED",
                    "OpenAI rejected the chat completion request (" + e.getStatusCode() + ") - not retry-safe", e);
        }
        catch (RuntimeException e) {
            throw new TransientProcessingException("OPENAI_CHAT_COMPLETION_FAILED",
                    "OpenAI chat completion failed", e);
        }
    }
}

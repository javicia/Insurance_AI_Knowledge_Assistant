package com.rag.springai.insuranceai.adapters.shared.llm;

import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Translates Spring AI's {@code ChatResponse} into the port's own {@link LlmCompletion}, shared
 * by {@code OpenAiLlmAdapter} and {@code AnthropicLlmAdapter} so both providers report token
 * usage the same way (the alternative - duplicating this null-handling twice - is exactly how
 * the two adapters would drift apart).
 *
 * <p><b>Why {@code ChatResponse} at all (FASE 27, benchmarking):</b> both adapters previously
 * called {@code chatModel.call(SystemMessage, UserMessage)}, whose {@code String} return value
 * is a convenience wrapper that throws away the very {@code ChatResponse} the provider filled
 * in - including {@code getMetadata().getUsage()}, the only place real token counts ever exist.
 * Text extraction here reproduces that wrapper's behaviour exactly ({@code
 * getResult().getOutput().getText()}, empty string when there is no generation), so switching to
 * the richer API is not a behaviour change for existing callers.
 *
 * <p><b>Defensive about usage, not about correctness:</b> {@code getMetadata()} and its {@code
 * getUsage()} are genuinely optional in the Spring AI contract, and individual counts inside a
 * present {@code Usage} may still be {@code null}. Every one of those cases maps to "no token
 * usage" ({@code null} counts), never to zeros - see {@link LlmCompletion}'s Javadoc for why
 * that distinction is load-bearing for cost figures.
 *
 * <p><b>{@link EmptyUsage} is the case that matters most</b>, and the only one that is not
 * obvious from the API: a freshly-built {@code ChatResponseMetadata} does not leave {@code usage}
 * {@code null}, it initialises it to an {@code EmptyUsage} whose {@code getPromptTokens()} and
 * {@code getCompletionTokens()} both return {@code 0}. Read naively, a provider that reported
 * nothing would therefore arrive here indistinguishable from one that genuinely consumed zero
 * tokens - and would be priced as a free call. It is mapped to "unknown" explicitly.
 */
public final class ChatResponseMapper {

    private ChatResponseMapper() {
    }

    public static LlmCompletion toCompletion(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return new LlmCompletion("");
        }
        Usage usage = usageOf(chatResponse);
        return new LlmCompletion(textOf(chatResponse), usage != null ? usage.getPromptTokens() : null,
                usage != null ? usage.getCompletionTokens() : null);
    }

    private static String textOf(ChatResponse chatResponse) {
        Generation generation = chatResponse.getResult();
        if (generation == null || generation.getOutput() == null) {
            return "";
        }
        String text = generation.getOutput().getText();
        return text != null ? text : "";
    }

    private static Usage usageOf(ChatResponse chatResponse) {
        ChatResponseMetadata metadata = chatResponse.getMetadata();
        Usage usage = metadata != null ? metadata.getUsage() : null;
        return usage instanceof EmptyUsage ? null : usage;
    }
}

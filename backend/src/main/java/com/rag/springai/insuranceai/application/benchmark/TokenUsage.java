package com.rag.springai.insuranceai.application.benchmark;

import com.rag.springai.insuranceai.domain.rag.LlmCompletion;

/**
 * A priced quantity of tokens (FASE 27, benchmarking). Separate from {@link LlmCompletion}'s own
 * nullable counts on purpose: by the time a {@code TokenUsage} exists, "unknown" has already
 * been resolved one way or the other, so every field is a real number and {@link
 * LlmCostCalculator} never has to decide what a {@code null} means.
 *
 * <p>{@code long}, not {@code int}: a projection over 10.000 requests routinely exceeds two
 * billion tokens.
 *
 * <p>{@code embeddingTokens} is carried here because a RAG request's real cost includes
 * embedding its question, which the chat provider's own usage metadata knows nothing about -
 * see {@link #ofCompletion} for why it is zero there rather than guessed.
 */
public record TokenUsage(long inputTokens, long outputTokens, long embeddingTokens) {

    public TokenUsage {
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(outputTokens, "outputTokens");
        requireNonNegative(embeddingTokens, "embeddingTokens");
    }

    /**
     * The usage a chat completion actually reported, with {@code embeddingTokens = 0} - not an
     * assumption that no embedding happened, but the honest statement that a chat response
     * carries no information about the embedding call that preceded it. Callers that measured
     * embedding tokens separately should add them via {@link #plus}.
     *
     * @throws IllegalStateException if the completion reported no usage - callers must check
     * {@link LlmCompletion#hasTokenUsage()} first (or use {@link
     * LlmCostCalculator#observedCostOf(LlmCompletion)}, which returns "not available" instead).
     */
    public static TokenUsage ofCompletion(LlmCompletion completion) {
        if (!completion.hasTokenUsage()) {
            throw new IllegalStateException(
                    "Cannot derive token usage from a completion whose provider reported none - check "
                            + "hasTokenUsage() first; absent usage must never be priced as 0 tokens");
        }
        return new TokenUsage(completion.inputTokens(), completion.outputTokens(), 0);
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                embeddingTokens + other.embeddingTokens);
    }

    public TokenUsage times(long factor) {
        if (factor < 0) {
            throw new IllegalArgumentException("factor must not be negative");
        }
        return new TokenUsage(inputTokens * factor, outputTokens * factor, embeddingTokens * factor);
    }

    private static void requireNonNegative(long tokens, String name) {
        if (tokens < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}

package com.rag.springai.insuranceai.domain.rag;

import java.util.Objects;

/**
 * Output of {@code LlmProvider}: the generated text plus, when the provider reported it, how
 * many tokens the call consumed. No vendor-specific response metadata leaks past the port
 * (brief section 6/7) - {@code inputTokens}/{@code outputTokens} are plain integers, not
 * Spring AI's {@code Usage} nor any SDK type.
 *
 * <p><b>Why token counts belong here (FASE 27, benchmarking):</b> they are not a benchmarking
 * concept bolted onto the result - they are simply part of what the provider answered, exactly
 * like the text itself. Anything downstream (cost calculation, metrics) derives from them, but
 * this record does not know or care about that. Keeping them here is also the only way to
 * capture them at all: {@code ChatResponse.getMetadata().getUsage()} exists solely inside the
 * adapter, and would be discarded the moment the adapter returned.
 *
 * <p><b>Why they are nullable, and why that matters:</b> not every provider fills usage in
 * (Spring AI's own {@code ChatResponseMetadata} may carry a {@code null} {@code Usage}, and
 * {@code FakeLlmAdapter} makes no real call at all, so it has nothing to report). {@code null}
 * here means "unknown", which is deliberately <em>not</em> the same as {@code 0}: a zero would
 * silently become a zero cost in {@code LlmCostCalculator} and quietly understate a benchmark.
 * Callers must therefore go through {@link #hasTokenUsage()} before reading the counts - and
 * {@link #totalTokens()} throws rather than inventing a number when they are absent.
 *
 * <p>The single-argument constructor keeps every caller that only ever cared about the text
 * (the fake adapter, the whole test suite, {@code AskInsuranceKnowledgeUseCase}'s own reading of
 * {@link #text()}) working unchanged.
 */
public record LlmCompletion(String text, Integer inputTokens, Integer outputTokens) {

    public LlmCompletion {
        Objects.requireNonNull(text, "text must not be null");
        requireNonNegativeIfPresent(inputTokens, "inputTokens");
        requireNonNegativeIfPresent(outputTokens, "outputTokens");
    }

    /** A completion from a provider that reported no token usage (see this record's Javadoc). */
    public LlmCompletion(String text) {
        this(text, null, null);
    }

    /**
     * {@code true} only when <em>both</em> counts are present. A half-reported usage (input but
     * no output, or the reverse) is treated as no usage at all rather than as a partial cost
     * basis, since neither a request cost nor a total is computable from half of it.
     */
    public boolean hasTokenUsage() {
        return inputTokens != null && outputTokens != null;
    }

    /**
     * @throws IllegalStateException if the provider reported no token usage - callers must check
     * {@link #hasTokenUsage()} first. Deliberately not {@code 0}: see this record's Javadoc.
     */
    public int totalTokens() {
        if (!hasTokenUsage()) {
            throw new IllegalStateException(
                    "This completion carries no token usage - check hasTokenUsage() first; 'unknown' must never "
                            + "be silently reported as 0 tokens");
        }
        return inputTokens + outputTokens;
    }

    private static void requireNonNegativeIfPresent(Integer tokens, String name) {
        if (tokens != null && tokens < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}

package com.rag.springai.insuranceai.application.benchmark;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A money amount broken down by what produced it, plus - crucially - {@link Basis}: whether it
 * was computed from tokens that were actually measured, or from an assumed per-request profile
 * multiplied out (FASE 27, benchmarking).
 *
 * <p>The two are numerically identical operations and would be indistinguishable once printed,
 * which is exactly why the distinction is a field of the result rather than a convention in the
 * calling code: "this run cost $2.14" and "1.000 runs like it would cost about $2.14" are very
 * different claims, and only one of them is evidence.
 *
 * @param tokenUsage the exact quantities this amount was computed from - kept so a figure can
 * always be traced back to its inputs rather than having to be believed
 */
public record LlmCost(BigDecimal inputCost, BigDecimal outputCost, BigDecimal embeddingCost, BigDecimal totalCost,
        String currency, Basis basis, TokenUsage tokenUsage) {

    public LlmCost {
        Objects.requireNonNull(inputCost, "inputCost must not be null");
        Objects.requireNonNull(outputCost, "outputCost must not be null");
        Objects.requireNonNull(embeddingCost, "embeddingCost must not be null");
        Objects.requireNonNull(totalCost, "totalCost must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(basis, "basis must not be null");
        Objects.requireNonNull(tokenUsage, "tokenUsage must not be null");
    }

    public enum Basis {

        /** Computed from token counts a provider really reported for calls that really happened. */
        OBSERVED,

        /** Extrapolated from an assumed per-request token profile - a forecast, not a measurement. */
        ESTIMATED
    }
}

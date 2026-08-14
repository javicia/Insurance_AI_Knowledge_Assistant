package com.rag.springai.insuranceai.application.benchmark;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Per-million-token unit prices used by {@link LlmCostCalculator} (FASE 27, benchmarking),
 * bound from the {@code insurance-ai.benchmark.pricing.*} namespace.
 *
 * <p><b>Why a separate {@code @ConfigurationProperties} record rather than another section of
 * {@code InsuranceAiProperties}:</b> that record is a required, fully-populated construction
 * argument of {@code AskInsuranceKnowledgeUseCase}/{@code HybridRetrievalService} and of the
 * dozen-odd unit tests that build it by hand; pricing is consumed by exactly one collaborator
 * and by nothing on the request path. Keeping it separate means benchmark pricing can change
 * without touching a single class that answers questions - the same reasoning that already
 * keeps {@code Rag}, {@code Security} and {@code Evaluation} as distinct sections rather than
 * one flat property bag.
 *
 * <p><b>About the defaults:</b> they are a <em>reference</em> taken from OpenAI's published list
 * prices (gpt-4o-mini chat, text-embedding-3-small) and are certain to go stale - vendor prices
 * change without notice, differ per model, per region and per contract, and batch/cached-input
 * tiers are cheaper still. They exist so the calculator is usable out of the box, not so anyone
 * can quote them: <b>verify against the tariff actually in force for the deployment's own
 * account and model before publishing any cost figure derived from them</b>, and override via
 * configuration. A cost computed from unverified unit prices is an arithmetic result, not a
 * measurement.
 *
 * <p>{@link BigDecimal}, not {@code double}: unit prices here are of the order of 1e-7 per
 * token, and binary floating point accumulates visible error once multiplied out over the
 * millions of tokens a projection deals with.
 *
 * @param currency purely a label carried through to {@link LlmCost} - no conversion is ever
 * performed, so mixing currencies across the three prices would produce a meaningless total.
 */
@ConfigurationProperties(prefix = "insurance-ai.benchmark.pricing")
public record LlmPricing(BigDecimal inputPricePerMillionTokens, BigDecimal outputPricePerMillionTokens,
        BigDecimal embeddingPricePerMillionTokens, String currency) {

    /** Reference only - verify against the tariff in force (see this record's Javadoc). */
    private static final BigDecimal REFERENCE_INPUT_PRICE_PER_MILLION = new BigDecimal("0.15");

    /** Reference only - verify against the tariff in force (see this record's Javadoc). */
    private static final BigDecimal REFERENCE_OUTPUT_PRICE_PER_MILLION = new BigDecimal("0.60");

    /** Reference only - verify against the tariff in force (see this record's Javadoc). */
    private static final BigDecimal REFERENCE_EMBEDDING_PRICE_PER_MILLION = new BigDecimal("0.02");

    private static final String DEFAULT_CURRENCY = "USD";

    public LlmPricing {
        inputPricePerMillionTokens = requireNonNegative(inputPricePerMillionTokens,
                REFERENCE_INPUT_PRICE_PER_MILLION, "input-price-per-million-tokens");
        outputPricePerMillionTokens = requireNonNegative(outputPricePerMillionTokens,
                REFERENCE_OUTPUT_PRICE_PER_MILLION, "output-price-per-million-tokens");
        embeddingPricePerMillionTokens = requireNonNegative(embeddingPricePerMillionTokens,
                REFERENCE_EMBEDDING_PRICE_PER_MILLION, "embedding-price-per-million-tokens");
        currency = Objects.requireNonNullElse(currency, DEFAULT_CURRENCY);
    }

    /** The reference defaults, for tests and for callers constructing this outside Spring. */
    public static LlmPricing reference() {
        return new LlmPricing(null, null, null, null);
    }

    private static BigDecimal requireNonNegative(BigDecimal value, BigDecimal fallback, String propertyName) {
        BigDecimal effective = value != null ? value : fallback;
        if (effective.signum() < 0) {
            throw new IllegalArgumentException(
                    "insurance-ai.benchmark.pricing." + propertyName + " must not be negative");
        }
        return effective;
    }
}

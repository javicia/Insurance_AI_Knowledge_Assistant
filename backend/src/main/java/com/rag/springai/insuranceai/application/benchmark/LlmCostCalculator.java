package com.rag.springai.insuranceai.application.benchmark;

import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns token counts into money, at the unit prices configured in {@link LlmPricing} (FASE 27,
 * benchmarking).
 *
 * <p><b>Why the application layer:</b> pricing is not a rule of the insurance domain - it is a
 * property of the vendor contract the deployment happens to be on, changes without any domain
 * event, and is read from configuration. Putting it in {@code domain} would also make the
 * package depend on Spring's {@code @ConfigurationProperties} binding, which
 * {@code ArchitectureTest.domainMustBeFrameworkFree} forbids outright. It is not an adapter
 * either: it talks to nothing outside the process. Application layer, in its own {@code
 * benchmark} package so it is obvious that nothing on the request path depends on it.
 *
 * <p><b>Observed vs estimated.</b> {@link #observedCostOf} prices tokens that were really
 * reported; {@link #projectedCostOf} multiplies an assumed per-request profile by a request
 * count. Every result says which it is ({@link LlmCost.Basis}) - see {@link LlmCost}.
 *
 * <p><b>Missing token usage is never priced as zero.</b> {@code FakeLlmAdapter} reports no
 * usage at all, and real providers sometimes omit it; treating that as "0 tokens" would produce
 * a confident $0.00 that looks exactly like a real, cheap measurement. {@link
 * #observedCostOf(LlmCompletion)} therefore returns {@link Optional#empty()} - "not available",
 * which a caller has to unwrap and so cannot silently add to a running total - and {@link
 * #observedCostOf(Collection)} reports how many completions it had to skip alongside the amount,
 * so a partially-instrumented run is visible as such rather than quietly understated.
 */
@Service
public class LlmCostCalculator {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    /**
     * Unit prices are of the order of 1e-7 per token, so division cannot be exact and needs a
     * bounded precision. 34 significant digits (DECIMAL128) is far more than money needs, but
     * rounding is deliberately left to whoever formats the figure: rounding to cents here would
     * turn every individual sub-cent request cost into 0.00 before it could ever be summed.
     */
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private final LlmPricing pricing;

    public LlmCostCalculator(LlmPricing pricing) {
        this.pricing = Objects.requireNonNull(pricing, "pricing must not be null");
    }

    /**
     * Observed cost of a single completion, or {@link Optional#empty()} when its provider
     * reported no token usage (see this class's Javadoc for why that is not a zero).
     */
    public Optional<LlmCost> observedCostOf(LlmCompletion completion) {
        Objects.requireNonNull(completion, "completion must not be null");
        return completion.hasTokenUsage()
                ? Optional.of(costOf(TokenUsage.ofCompletion(completion), LlmCost.Basis.OBSERVED))
                : Optional.empty();
    }

    /** Observed cost of token counts measured elsewhere (e.g. including embedding tokens). */
    public LlmCost observedCostOf(TokenUsage usage) {
        return costOf(usage, LlmCost.Basis.OBSERVED);
    }

    /**
     * Observed cost of a whole benchmark run, summing only the completions that actually carry
     * usage and reporting the rest as {@link ObservedRunCost#completionsWithoutTokenUsage()}
     * rather than as free requests.
     */
    public ObservedRunCost observedCostOf(Collection<LlmCompletion> completions) {
        Objects.requireNonNull(completions, "completions must not be null");
        TokenUsage total = new TokenUsage(0, 0, 0);
        int priced = 0;
        int withoutUsage = 0;
        for (LlmCompletion completion : completions) {
            if (completion.hasTokenUsage()) {
                total = total.plus(TokenUsage.ofCompletion(completion));
                priced++;
            }
            else {
                withoutUsage++;
            }
        }
        return new ObservedRunCost(costOf(total, LlmCost.Basis.OBSERVED), priced, withoutUsage);
    }

    /**
     * Projected cost of {@code requestCount} future requests, each assumed to consume {@code
     * averageUsagePerRequest}. The result is {@link LlmCost.Basis#ESTIMATED}: it is only ever as
     * good as that average, which a real projection should take from a measured run (e.g. the
     * mean of {@code rag.llm.tokens.input}/{@code .output} over a benchmark) rather than invent.
     */
    public LlmCost projectedCostOf(long requestCount, TokenUsage averageUsagePerRequest) {
        if (requestCount < 0) {
            throw new IllegalArgumentException("requestCount must not be negative");
        }
        Objects.requireNonNull(averageUsagePerRequest, "averageUsagePerRequest must not be null");
        return costOf(averageUsagePerRequest.times(requestCount), LlmCost.Basis.ESTIMATED);
    }

    /**
     * Projected cost of {@code requestCount} requests that each look like an already-observed
     * one - the common "this request cost X, what would 10.000 of them cost" question. Still
     * {@link LlmCost.Basis#ESTIMATED}: one observed request is a sample of one.
     */
    public Optional<LlmCost> projectedCostOf(long requestCount, LlmCompletion representativeCompletion) {
        Objects.requireNonNull(representativeCompletion, "representativeCompletion must not be null");
        return representativeCompletion.hasTokenUsage()
                ? Optional.of(projectedCostOf(requestCount, TokenUsage.ofCompletion(representativeCompletion)))
                : Optional.empty();
    }

    private LlmCost costOf(TokenUsage usage, LlmCost.Basis basis) {
        Objects.requireNonNull(usage, "usage must not be null");
        BigDecimal input = price(usage.inputTokens(), pricing.inputPricePerMillionTokens());
        BigDecimal output = price(usage.outputTokens(), pricing.outputPricePerMillionTokens());
        BigDecimal embedding = price(usage.embeddingTokens(), pricing.embeddingPricePerMillionTokens());
        return new LlmCost(input, output, embedding, input.add(output).add(embedding), pricing.currency(), basis,
                usage);
    }

    private BigDecimal price(long tokens, BigDecimal pricePerMillionTokens) {
        return BigDecimal.valueOf(tokens).multiply(pricePerMillionTokens).divide(ONE_MILLION, PRECISION);
    }

    /**
     * @param completionsWithoutTokenUsage completions excluded from {@code cost} because their
     * provider reported nothing - a non-zero value here means the amount is a lower bound, not
     * the run's full cost, and saying so is the whole point of returning it
     */
    public record ObservedRunCost(LlmCost cost, int completionsPriced, int completionsWithoutTokenUsage) {

        /** {@code true} when every completion in the run contributed to {@link #cost}. */
        public boolean isComplete() {
            return completionsWithoutTokenUsage == 0;
        }
    }
}

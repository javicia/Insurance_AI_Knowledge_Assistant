package com.rag.springai.insuranceai.application.benchmark;

import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 27 (benchmarking). The arithmetic here is trivial by design - what these tests actually
 * pin down is the honesty of the result: that prices really come from configuration rather than
 * from a constant, that an estimate is never returned looking like a measurement, and above all
 * that a completion whose provider reported no tokens produces "not available" rather than a
 * plausible-looking $0.00.
 */
class LlmCostCalculatorTest {

    /** Deliberately round, so an arithmetic slip is visible rather than hidden in a rounding tail. */
    private static final LlmPricing TEST_PRICING = new LlmPricing(new BigDecimal("1.00"), new BigDecimal("2.00"),
            new BigDecimal("0.10"), "EUR");

    private final LlmCostCalculator calculator = new LlmCostCalculator(TEST_PRICING);

    private static void assertAmount(String expected, BigDecimal actual) {
        // compareTo, not equals: BigDecimal.equals is scale-sensitive (0.45 != 0.450), and the
        // scale here is an artefact of the division, not part of what is being asserted.
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void pricesASingleRequestFromTheTokensTheProviderActuallyReported() {
        LlmCompletion completion = new LlmCompletion("answer", 1_000_000, 500_000);

        LlmCost cost = calculator.observedCostOf(completion).orElseThrow();

        assertAmount("1.00", cost.inputCost());
        assertAmount("1.00", cost.outputCost());
        assertAmount("2.00", cost.totalCost());
        assertEquals(LlmCost.Basis.OBSERVED, cost.basis());
        assertEquals("EUR", cost.currency());
    }

    @Test
    void pricesEmbeddingTokensSeparatelyFromChatTokens() {
        LlmCost cost = calculator.observedCostOf(new TokenUsage(1_000_000, 0, 2_000_000));

        assertAmount("0.20", cost.embeddingCost());
        assertAmount("1.20", cost.totalCost());
    }

    @Test
    void projectsTheCostOfAThousandAndOfTenThousandRequests() {
        TokenUsage perRequest = new TokenUsage(1_000, 500, 100);

        LlmCost thousand = calculator.projectedCostOf(1_000, perRequest);
        LlmCost tenThousand = calculator.projectedCostOf(10_000, perRequest);

        // 1.000 x (1.000 x 1.00 + 500 x 2.00 + 100 x 0.10) / 1e6 = 1.00 + 1.00 + 0.01
        assertAmount("2.01", thousand.totalCost());
        assertAmount("20.10", tenThousand.totalCost());
        assertEquals(1_000_000, thousand.tokenUsage().inputTokens(),
                "the projected token quantities must travel with the amount, so a figure can be traced back");
    }

    @Test
    void aProjectionIsLabelledEstimatedEvenWhenItStartsFromAnObservedRequest() {
        LlmCompletion observed = new LlmCompletion("answer", 1_000, 500);

        LlmCost projected = calculator.projectedCostOf(10_000, observed).orElseThrow();

        assertEquals(LlmCost.Basis.ESTIMATED, projected.basis(),
                "extrapolating from a single observed request is still a forecast, not a measurement");
    }

    @Test
    void usesTheConfiguredPricesNotHardcodedOnes() {
        LlmCostCalculator tenTimesDearer = new LlmCostCalculator(
                new LlmPricing(new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("1.00"), "USD"));

        LlmCost cost = tenTimesDearer.observedCostOf(new TokenUsage(1_000_000, 500_000, 0));

        assertAmount("20.00", cost.totalCost());
        assertEquals("USD", cost.currency());
    }

    @Test
    void theReferenceDefaultsApplyWhenNoPriceIsConfigured() {
        LlmCost cost = new LlmCostCalculator(LlmPricing.reference()).observedCostOf(new TokenUsage(1_000_000, 0, 0));

        // Only asserts that the documented reference default is what actually gets used - not
        // that 0.15/M is a correct price today, which no test can know (see LlmPricing).
        assertAmount("0.15", cost.totalCost());
        assertEquals("USD", cost.currency());
    }

    @Test
    void aNegativeConfiguredPriceIsRejectedRatherThanProducingNegativeCosts() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmPricing(new BigDecimal("-1.00"), BigDecimal.ONE, BigDecimal.ONE, "USD"));
    }

    // --- absent token usage must never become a fake zero ---

    @Test
    void aCompletionWithoutTokenUsageHasNoCostAtAllRatherThanACostOfZero() {
        Optional<LlmCost> cost = calculator.observedCostOf(new LlmCompletion("answer from the fake provider"));

        assertTrue(cost.isEmpty(),
                "a provider that reported nothing must yield 'not available' - a 0.00 would be indistinguishable "
                        + "from a real, free call and would silently understate a benchmark");
    }

    @Test
    void aProjectionFromACompletionWithoutTokenUsageIsAlsoUnavailable() {
        assertTrue(calculator.projectedCostOf(10_000, new LlmCompletion("answer")).isEmpty());
    }

    @Test
    void derivingTokenUsageFromACompletionThatHasNoneFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> TokenUsage.ofCompletion(new LlmCompletion("answer")));
    }

    @Test
    void aRunCostReportsHowManyCompletionsItCouldNotPriceInsteadOfSkippingThemSilently() {
        List<LlmCompletion> run = List.of(new LlmCompletion("a", 1_000_000, 0), new LlmCompletion("b"),
                new LlmCompletion("c", 1_000_000, 0));

        LlmCostCalculator.ObservedRunCost runCost = calculator.observedCostOf(run);

        assertAmount("2.00", runCost.cost().totalCost());
        assertEquals(2, runCost.completionsPriced());
        assertEquals(1, runCost.completionsWithoutTokenUsage());
        assertFalse(runCost.isComplete(), "one unpriced completion makes the amount a lower bound, not the run cost");
    }

    @Test
    void aFullyInstrumentedRunIsReportedAsComplete() {
        LlmCostCalculator.ObservedRunCost runCost = calculator
                .observedCostOf(List.of(new LlmCompletion("a", 10, 10), new LlmCompletion("b", 10, 10)));

        assertTrue(runCost.isComplete());
        assertEquals(0, runCost.completionsWithoutTokenUsage());
    }

    @Test
    void anEmptyRunCostsZeroBecauseNothingWasRunNotBecauseUsageWasMissing() {
        LlmCostCalculator.ObservedRunCost runCost = calculator.observedCostOf(List.of());

        assertAmount("0", runCost.cost().totalCost());
        assertTrue(runCost.isComplete());
        assertEquals(0, runCost.completionsPriced());
    }
}

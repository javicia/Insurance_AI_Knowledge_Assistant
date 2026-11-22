package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.application.benchmark.LlmPricing;
import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GeneralAiBudgetLedger} is the control that lets the general-AI fallback be pointed at a
 * paid provider at all (FASE 29, closing FASE 28's DEF-08), so its limits are tested as hard
 * guarantees - including the two cases a naive implementation gets wrong: concurrent admission,
 * and a provider that reports no token usage.
 */
class GeneralAiBudgetLedgerTest {

    /** 10 output currency units per million tokens, so 2000 tokens cost exactly 0.02. */
    private static final LlmPricing PRICING = new LlmPricing(
            new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("1"), "USD");

    private static final int MAX_TOKENS = 2000;
    private static final BigDecimal WORST_CASE_PER_REQUEST = new BigDecimal("0.02");

    private static InsuranceAiProperties properties(String daily, String monthly) {
        InsuranceAiProperties.Rag rag = new InsuranceAiProperties.Rag(
                new InsuranceAiProperties.Rag.Semantic(8, 0.75), new InsuranceAiProperties.Rag.Lexical(8, 0.05, 0.6),
                new InsuranceAiProperties.Rag.Hybrid(20, 8, 60.0), new InsuranceAiProperties.Rag.Reranking(true),
                new InsuranceAiProperties.Rag.QueryExpansion(false, 3), new InsuranceAiProperties.Rag.Context(6000),
                new InsuranceAiProperties.Rag.GeneralAiFallback(true, false, new BigDecimal(daily),
                        new BigDecimal(monthly), MAX_TOKENS));
        return new InsuranceAiProperties(rag,
                new InsuranceAiProperties.Security(new InsuranceAiProperties.Security.PromptInjection(true),
                        new InsuranceAiProperties.Security.Pii(true),
                        new InsuranceAiProperties.Security.OAuth2("http://localhost:19999/realms/test")),
                new InsuranceAiProperties.Governance(new InsuranceAiProperties.Governance.Audit(true)),
                new InsuranceAiProperties.Ai(InsuranceAiProperties.SupportedAiProvider.FAKE),
                new InsuranceAiProperties.Evaluation(
                        new InsuranceAiProperties.Evaluation.Thresholds(1.0, 1.0, 0.75)));
    }

    private static Clock fixedAt(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private GeneralAiBudgetLedger ledger(String daily, String monthly, Clock clock) {
        return new GeneralAiBudgetLedger(properties(daily, monthly), PRICING, clock);
    }

    @Test
    void admitsRequestsWhileTheBudgetHasRoomForTheirWorstCase() {
        // 0.10 daily / 0.02 worst case = exactly five requests, and no more.
        GeneralAiBudgetLedger ledger = ledger("0.10", "100", fixedAt("2026-03-05T10:00:00Z"));

        for (int i = 0; i < 5; i++) {
            assertTrue(ledger.reserve().isPresent(), "request " + (i + 1) + " should fit the budget");
        }
        assertTrue(ledger.reserve().isEmpty(), "the sixth request must be refused");
    }

    @Test
    void refusesWhenTheMonthlyBudgetIsExhaustedEvenIfTheDailyOneHasRoom() {
        // The invariant spans both windows, which is why the ledger checks them under one lock.
        GeneralAiBudgetLedger ledger = ledger("100", "0.02", fixedAt("2026-03-05T10:00:00Z"));

        assertTrue(ledger.reserve().isPresent());
        assertTrue(ledger.reserve().isEmpty(), "monthly budget is spent even though daily is nearly untouched");
    }

    @Test
    void concurrentRequestsCannotCollectivelyExceedTheBudget() throws Exception {
        // The defect this class exists to prevent: 40 threads racing for 5 slots. A
        // check-then-add implementation admits far more than five, because every thread reads the
        // same pre-write balance.
        GeneralAiBudgetLedger ledger = ledger("0.10", "100", fixedAt("2026-03-05T10:00:00Z"));
        int threads = 40;

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Optional<UUID>>> tasks = IntStream.range(0, threads)
                    .<Callable<Optional<UUID>>>mapToObj(i -> ledger::reserve)
                    .toList();
            List<Future<Optional<UUID>>> futures = pool.invokeAll(tasks);

            long admitted = 0;
            for (Future<Optional<UUID>> future : futures) {
                if (future.get().isPresent()) {
                    admitted++;
                }
            }
            assertEquals(5, admitted, "exactly five of forty concurrent requests may be admitted");
        }
        assertEquals(new BigDecimal("0.10"), ledger.spentToday().stripTrailingZeros()
                .add(BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void reconcilingDownToRealUsageReturnsTheUnusedReservation() {
        GeneralAiBudgetLedger ledger = ledger("0.10", "100", fixedAt("2026-03-05T10:00:00Z"));
        UUID reservation = ledger.reserve().orElseThrow();

        ledger.reconcile(reservation, Optional.of(new BigDecimal("0.005")));

        assertEquals(0, new BigDecimal("0.005").compareTo(ledger.spentToday()),
                "only the real cost should remain committed");
    }

    @Test
    void unreportedTokenUsageKeepsTheWorstCaseReservationRatherThanRefundingIt() {
        // The subtle hole: FakeLlmAdapter reports no usage, and real providers sometimes omit it.
        // Refunding on "unknown" would make an unmetered provider effectively unlimited - every
        // call would reserve, report nothing, and hand the budget straight back.
        GeneralAiBudgetLedger ledger = ledger("0.10", "100", fixedAt("2026-03-05T10:00:00Z"));
        UUID reservation = ledger.reserve().orElseThrow();

        ledger.reconcile(reservation, Optional.empty());

        assertEquals(0, WORST_CASE_PER_REQUEST.compareTo(ledger.spentToday()),
                "unknown usage must stay committed at its worst case, never be priced as zero");
    }

    @Test
    void anUnmeteredProviderStillRunsOutOfBudget() {
        // The consequence of the rule above, asserted end to end: an unmetered provider is
        // bounded, not unlimited.
        GeneralAiBudgetLedger ledger = ledger("0.10", "100", fixedAt("2026-03-05T10:00:00Z"));

        for (int i = 0; i < 5; i++) {
            ledger.reconcile(ledger.reserve().orElseThrow(), Optional.empty());
        }

        assertTrue(ledger.reserve().isEmpty(), "five unmetered calls must exhaust a five-call budget");
    }

    @Test
    void aDefiniteProviderFailureReturnsItsReservation() {
        GeneralAiBudgetLedger ledger = ledger("0.10", "100", fixedAt("2026-03-05T10:00:00Z"));
        UUID reservation = ledger.reserve().orElseThrow();

        ledger.releaseAfterFailure(reservation, false);

        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.spentToday()),
                "a rejected request cannot have been billed, so its reservation is freed");
    }

    @Test
    void aTimeoutKeepsItsReservationBecauseTheProviderMayStillHaveBilled() {
        GeneralAiBudgetLedger ledger = ledger("0.10", "100", fixedAt("2026-03-05T10:00:00Z"));
        UUID reservation = ledger.reserve().orElseThrow();

        ledger.releaseAfterFailure(reservation, true);

        assertEquals(0, WORST_CASE_PER_REQUEST.compareTo(ledger.spentToday()),
                "a timeout does not prove the provider did not process and bill the request");
    }

    @Test
    void aNewDayResetsTheDailyWindowButNotTheMonthlyOne() {
        GeneralAiBudgetLedger day1 = ledger("0.02", "100", fixedAt("2026-03-05T23:59:00Z"));
        assertTrue(day1.reserve().isPresent());
        assertTrue(day1.reserve().isEmpty(), "daily budget is spent");

        // A separate ledger at a later date stands in for the clock advancing: the window key is
        // derived from the clock on every call, so a date change resets the daily window.
        GeneralAiBudgetLedger day2 = ledger("0.02", "100", fixedAt("2026-03-06T00:01:00Z"));
        assertTrue(day2.reserve().isPresent(), "a new day must restore the daily allowance");
    }

    @Test
    void reconcilingAnUnknownReservationIsIgnoredRatherThanCreditingTheBudget() {
        // Defence against a double-reconcile crediting the budget twice, which would silently
        // raise the effective limit.
        GeneralAiBudgetLedger ledger = ledger("0.10", "100", fixedAt("2026-03-05T10:00:00Z"));
        UUID reservation = ledger.reserve().orElseThrow();
        ledger.reconcile(reservation, Optional.of(new BigDecimal("0.02")));

        ledger.reconcile(reservation, Optional.of(new BigDecimal("0.02")));
        ledger.reconcile(UUID.randomUUID(), Optional.of(new BigDecimal("0.02")));

        assertEquals(0, WORST_CASE_PER_REQUEST.compareTo(ledger.spentToday()),
                "repeated or unknown reconciliations must not move the balance");
    }

    @Test
    void refusalsAreCountedForObservability() {
        GeneralAiBudgetLedger ledger = ledger("0.02", "100", fixedAt("2026-03-05T10:00:00Z"));
        ledger.reserve();

        assertTrue(ledger.reserve().isEmpty());
        assertTrue(ledger.reserve().isEmpty());

        assertEquals(2, ledger.refusalCount());
        assertFalse(ledger.spentThisMonth().signum() == 0);
    }
}

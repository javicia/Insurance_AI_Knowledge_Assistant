package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.application.benchmark.LlmPricing;
import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spend control for the general-AI fallback path (FASE 29, closing FASE 28's DEF-08). The
 * fallback was implemented without one, which is why it had to stay disabled: enabling it against
 * a paid provider meant an unbounded per-question spend with no way to stop it in flight.
 *
 * <p><b>Reserve, then reconcile.</b> A naive "add the cost after the call" ledger cannot enforce
 * a limit, because the money is already spent by the time it is counted, and two concurrent
 * requests both see a budget that still has room. So every request must first {@link #reserve}
 * the <em>worst case</em> it could cost - {@code max-tokens-per-request} priced as if every token
 * were an output token, which is the more expensive side of every tariff - and only afterwards
 * {@link #reconcile} that reservation down to what the provider actually reported. A request that
 * cannot reserve is refused before the provider is ever called.
 *
 * <p>Over-reserving is the deliberate direction of error: it can refuse a request that would have
 * fitted, which is recoverable, rather than admit one that does not, which is not.
 *
 * <p><b>Unknown usage is never reconciled to zero.</b> {@code FakeLlmAdapter} reports no token
 * usage and real providers sometimes omit it. Releasing the reservation in that case would make
 * an unmetered provider effectively unlimited - every call would reserve, report nothing, and
 * give the budget straight back. So when usage is absent the reservation is <em>kept</em> at its
 * worst-case value and counted as spent. That is the same principle {@code LlmCostCalculator}
 * already applies to reporting (never price unknown usage as zero), applied to enforcement.
 *
 * <p><b>A failed call releases its reservation; a timeout does not.</b> If the provider rejected
 * the request outright it cannot have billed for it, so the money is returned. A timeout is
 * different: the request may well have been processed and billed, and this side has no way to
 * find out, so {@link #releaseAfterFailure} keeps the reservation for a timeout and only frees it
 * for a definite failure. Guessing in the cheaper direction would let a provider under sustained
 * timeout burn an unlimited amount while the ledger reported no spend at all.
 *
 * <p><b>Scope limitation, stated rather than implied:</b> this ledger is in-memory and therefore
 * per-instance. Two backend replicas each enforce the configured limit independently, so a
 * two-replica deployment can spend up to twice it. Making the limit cluster-wide needs shared
 * state (a database row with {@code SELECT ... FOR UPDATE}, or Redis) and is deliberately not
 * built here: this PoC runs a single backend container, and an in-memory ledger that is honest
 * about its scope is better than a distributed one that is not exercised. See
 * {@code docs/operations/FASE_29_PENDIENTES_ES.md}.
 */
@Component
public class GeneralAiBudgetLedger {

    private static final Logger log = LoggerFactory.getLogger(GeneralAiBudgetLedger.class);
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private final InsuranceAiProperties properties;
    private final LlmPricing pricing;
    private final Clock clock;

    private final ConcurrentHashMap<UUID, Reservation> open = new ConcurrentHashMap<>();
    private final AtomicLong refusals = new AtomicLong();

    /**
     * Guards the whole check-and-reserve sequence. A lock rather than two atomics because the
     * invariant spans both windows: a request must be admitted only if it fits the daily
     * <em>and</em> the monthly budget, and checking them independently would let a request that
     * fits neither slip through between the two reads.
     */
    private final Object lock = new Object();

    private Window daily = Window.empty();
    private Window monthly = Window.empty();

    public GeneralAiBudgetLedger(InsuranceAiProperties properties, LlmPricing pricing, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.pricing = Objects.requireNonNull(pricing, "pricing must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Reserves the worst-case cost of one general-AI request, or returns {@link Optional#empty()}
     * when it would breach either configured budget.
     */
    public Optional<UUID> reserve() {
        InsuranceAiProperties.Rag.GeneralAiFallback config = properties.rag().generalAiFallback();
        BigDecimal worstCase = worstCaseCostPerRequest();

        synchronized (lock) {
            rollOverIfPeriodChanged();

            BigDecimal dailyLimit = config.dailyBudget();
            BigDecimal monthlyLimit = config.monthlyBudget();
            if (daily.committed.add(worstCase).compareTo(dailyLimit) > 0
                    || monthly.committed.add(worstCase).compareTo(monthlyLimit) > 0) {
                refusals.incrementAndGet();
                log.warn("General-AI fallback refused by budget: worstCase={} dailySpent={}/{} monthlySpent={}/{}",
                        worstCase, daily.committed, dailyLimit, monthly.committed, monthlyLimit);
                return Optional.empty();
            }

            UUID id = UUID.randomUUID();
            daily = daily.plus(worstCase);
            monthly = monthly.plus(worstCase);
            open.put(id, new Reservation(worstCase, daily.key, monthly.key));
            return Optional.of(id);
        }
    }

    /**
     * Replaces a reservation with the real cost of the tokens the provider reported. When {@code
     * observedCost} is empty - the provider reported no usage - the reservation is kept at its
     * worst-case value, for the reason given in this class's Javadoc.
     */
    public void reconcile(UUID reservationId, Optional<BigDecimal> observedCost) {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Reservation reservation = open.remove(reservationId);
        if (reservation == null) {
            return;
        }
        if (observedCost.isEmpty()) {
            log.debug("Provider reported no token usage - keeping the worst-case reservation {} as spent, "
                    + "since releasing it would make an unmetered provider unlimited", reservation.amount);
            return;
        }
        adjust(reservation, reservation.amount.subtract(observedCost.get()));
    }

    /**
     * Frees a reservation whose provider call definitely failed. A {@code timedOut} call keeps
     * its reservation: the provider may have processed and billed it, and this side cannot tell.
     */
    public void releaseAfterFailure(UUID reservationId, boolean timedOut) {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Reservation reservation = open.remove(reservationId);
        if (reservation == null) {
            return;
        }
        if (timedOut) {
            log.warn("General-AI call timed out - keeping its {} reservation, since a timeout does not prove "
                    + "the provider did not bill for the request", reservation.amount);
            return;
        }
        adjust(reservation, reservation.amount);
    }

    /** Spend committed in the current day/month, for metrics and reporting. */
    public BigDecimal spentToday() {
        synchronized (lock) {
            rollOverIfPeriodChanged();
            return daily.committed;
        }
    }

    public BigDecimal spentThisMonth() {
        synchronized (lock) {
            rollOverIfPeriodChanged();
            return monthly.committed;
        }
    }

    public long refusalCount() {
        return refusals.get();
    }

    /**
     * Returns {@code refund} to whichever windows the reservation was taken from - and to no
     * others. A reservation opened yesterday and reconciled today must not credit today's budget,
     * which would let a long-running request inflate the new window's headroom.
     */
    private void adjust(Reservation reservation, BigDecimal refund) {
        if (refund.signum() == 0) {
            return;
        }
        synchronized (lock) {
            if (daily.key.equals(reservation.dailyKey)) {
                daily = daily.minus(refund);
            }
            if (monthly.key.equals(reservation.monthlyKey)) {
                monthly = monthly.minus(refund);
            }
        }
    }

    private void rollOverIfPeriodChanged() {
        String today = LocalDate.now(clock).toString();
        String thisMonth = YearMonth.now(clock).toString();
        if (!today.equals(daily.key)) {
            daily = new Window(today, BigDecimal.ZERO);
        }
        if (!thisMonth.equals(monthly.key)) {
            monthly = new Window(thisMonth, BigDecimal.ZERO);
        }
    }

    /**
     * The most a single request could cost: every allowed token priced at the output rate, which
     * is the dearer of the two on every tariff this project has seen. Pricing the input portion
     * cheaply would require knowing the split before the call, which is exactly what is unknown.
     */
    private BigDecimal worstCaseCostPerRequest() {
        int maxTokens = properties.rag().generalAiFallback().maxTokensPerRequest();
        return pricing.outputPricePerMillionTokens()
                .multiply(BigDecimal.valueOf(maxTokens), PRECISION)
                .divide(ONE_MILLION, PRECISION);
    }

    private record Reservation(BigDecimal amount, String dailyKey, String monthlyKey) {
    }

    private record Window(String key, BigDecimal committed) {
        static Window empty() {
            return new Window("", BigDecimal.ZERO);
        }

        Window plus(BigDecimal amount) {
            return new Window(key, committed.add(amount));
        }

        Window minus(BigDecimal amount) {
            BigDecimal next = committed.subtract(amount);
            return new Window(key, next.signum() < 0 ? BigDecimal.ZERO : next);
        }
    }
}

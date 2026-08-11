package com.rag.springai.insuranceai.domain.document;

import com.rag.springai.insuranceai.domain.document.exception.InvalidEffectivePeriodException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectivePeriodTest {

    private final Instant day1 = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant day10 = day1.plus(9, ChronoUnit.DAYS);
    private final Instant day20 = day1.plus(19, ChronoUnit.DAYS);
    private final Instant day30 = day1.plus(29, ChronoUnit.DAYS);

    @Test
    void rejectsAnEffectiveToBeforeEffectiveFrom() {
        assertThrows(InvalidEffectivePeriodException.class, () -> EffectivePeriod.of(day10, day1));
    }

    @Test
    void anEffectiveToEqualToEffectiveFromIsAllowedButNeverEffective() {
        EffectivePeriod period = EffectivePeriod.of(day10, day10);

        assertFalse(period.isEffectiveAt(day10));
    }

    @Test
    void openEndedPeriodIsEffectiveIndefinitelyAfterItStarts() {
        EffectivePeriod period = EffectivePeriod.startingAt(day10);

        assertFalse(period.isEffectiveAt(day1));
        assertTrue(period.isEffectiveAt(day10));
        assertTrue(period.isEffectiveAt(day30));
    }

    @Test
    void closedPeriodIsEffectiveOnlyWithinItsBounds() {
        EffectivePeriod period = EffectivePeriod.of(day10, day20);

        assertFalse(period.isEffectiveAt(day1));
        assertTrue(period.isEffectiveAt(day10));
        assertTrue(period.isEffectiveAt(day10.plus(1, ChronoUnit.DAYS)));
        assertFalse(period.isEffectiveAt(day20));
        assertFalse(period.isEffectiveAt(day30));
    }

    @Test
    void nonOverlappingSequentialPeriodsDoNotOverlap() {
        EffectivePeriod first = EffectivePeriod.of(day1, day10);
        EffectivePeriod second = EffectivePeriod.of(day10, day20);

        assertFalse(first.overlaps(second));
        assertFalse(second.overlaps(first));
    }

    @Test
    void overlappingPeriodsAreDetectedInBothDirections() {
        EffectivePeriod first = EffectivePeriod.of(day1, day20);
        EffectivePeriod second = EffectivePeriod.of(day10, day30);

        assertTrue(first.overlaps(second));
        assertTrue(second.overlaps(first));
    }

    @Test
    void anOpenEndedPeriodOverlapsAnyLaterPeriod() {
        EffectivePeriod openEnded = EffectivePeriod.startingAt(day1);
        EffectivePeriod later = EffectivePeriod.of(day20, day30);

        assertTrue(openEnded.overlaps(later));
        assertTrue(later.overlaps(openEnded));
    }

    @Test
    void twoOpenEndedPeriodsAlwaysOverlap() {
        EffectivePeriod first = EffectivePeriod.startingAt(day1);
        EffectivePeriod second = EffectivePeriod.startingAt(day10);

        assertTrue(first.overlaps(second));
    }
}

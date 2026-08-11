package com.rag.springai.insuranceai.domain.document;

import com.rag.springai.insuranceai.domain.document.exception.InvalidEffectivePeriodException;

import java.time.Instant;
import java.util.Objects;

/**
 * The time range during which a {@code DocumentVersion} is the version employees should rely
 * on (brief section 9/11). {@code effectiveTo == null} means "effective indefinitely, until
 * superseded" - the normal state of the current version of a document.
 *
 * <p>Lets the domain answer "which version is effective right now" (brief section 9) with
 * {@link #isEffectiveAt(Instant)} alone, with no SQL involved, and lets {@code Document}
 * reject conflicting versions with {@link #overlaps(EffectivePeriod)}.
 */
public record EffectivePeriod(Instant effectiveFrom, Instant effectiveTo) {

    public EffectivePeriod {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new InvalidEffectivePeriodException(effectiveFrom, effectiveTo);
        }
    }

    public static EffectivePeriod startingAt(Instant effectiveFrom) {
        return new EffectivePeriod(effectiveFrom, null);
    }

    public static EffectivePeriod of(Instant effectiveFrom, Instant effectiveTo) {
        return new EffectivePeriod(effectiveFrom, effectiveTo);
    }

    public boolean isEffectiveAt(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return !instant.isBefore(effectiveFrom) && (effectiveTo == null || instant.isBefore(effectiveTo));
    }

    public boolean overlaps(EffectivePeriod other) {
        Objects.requireNonNull(other, "other must not be null");
        Instant thisEnd = this.effectiveTo == null ? Instant.MAX : this.effectiveTo;
        Instant otherEnd = other.effectiveTo == null ? Instant.MAX : other.effectiveTo;
        return this.effectiveFrom.isBefore(otherEnd) && other.effectiveFrom.isBefore(thisEnd);
    }
}

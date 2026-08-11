package com.rag.springai.insuranceai.domain.document.exception;

import com.rag.springai.insuranceai.domain.shared.exception.DomainException;

import java.time.Instant;

/**
 * Raised when constructing an {@code EffectivePeriod} whose {@code effectiveTo} is before its
 * {@code effectiveFrom}.
 */
public final class InvalidEffectivePeriodException extends DomainException {

    public InvalidEffectivePeriodException(Instant effectiveFrom, Instant effectiveTo) {
        super("DOCUMENT_VERSION_INVALID_EFFECTIVE_PERIOD",
                "effectiveTo (" + effectiveTo + ") must not be before effectiveFrom (" + effectiveFrom + ")");
    }
}

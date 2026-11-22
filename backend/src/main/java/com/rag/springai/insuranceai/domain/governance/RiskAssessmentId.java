package com.rag.springai.insuranceai.domain.governance;

import java.util.Objects;
import java.util.UUID;

public record RiskAssessmentId(UUID value) {

    public RiskAssessmentId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static RiskAssessmentId generate() {
        return new RiskAssessmentId(UUID.randomUUID());
    }

    public static RiskAssessmentId of(String value) {
        return new RiskAssessmentId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

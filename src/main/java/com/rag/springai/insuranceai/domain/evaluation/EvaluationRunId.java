package com.rag.springai.insuranceai.domain.evaluation;

import java.util.Objects;
import java.util.UUID;

public record EvaluationRunId(UUID value) {

    public EvaluationRunId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static EvaluationRunId generate() {
        return new EvaluationRunId(UUID.randomUUID());
    }

    public static EvaluationRunId of(String value) {
        return new EvaluationRunId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

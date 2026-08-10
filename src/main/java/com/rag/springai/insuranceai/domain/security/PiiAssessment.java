package com.rag.springai.insuranceai.domain.security;

import java.util.List;
import java.util.Objects;

public record PiiAssessment(boolean detected, List<PiiMatch> matches) {

    public PiiAssessment {
        Objects.requireNonNull(matches, "matches must not be null");
        matches = List.copyOf(matches);
    }

    public static PiiAssessment clean() {
        return new PiiAssessment(false, List.of());
    }
}

package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;
import com.rag.springai.insuranceai.domain.rag.RetrievalDiagnostics;

import java.util.List;
import java.util.Objects;

/** {@code HybridRetrievalService.retrieve}'s result: final context candidates plus diagnostics. */
public record HybridRetrievalOutcome(List<HybridRetrievalResult> finalCandidates, RetrievalDiagnostics diagnostics) {

    public HybridRetrievalOutcome {
        Objects.requireNonNull(finalCandidates, "finalCandidates must not be null");
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        finalCandidates = List.copyOf(finalCandidates);
    }
}

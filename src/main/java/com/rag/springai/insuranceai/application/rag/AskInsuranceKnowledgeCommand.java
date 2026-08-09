package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.shared.TraceId;

import java.util.Objects;

public record AskInsuranceKnowledgeCommand(String question, TraceId traceId) {

    public AskInsuranceKnowledgeCommand {
        Objects.requireNonNull(question, "question must not be null");
        if (question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        Objects.requireNonNull(traceId, "traceId must not be null");
    }
}

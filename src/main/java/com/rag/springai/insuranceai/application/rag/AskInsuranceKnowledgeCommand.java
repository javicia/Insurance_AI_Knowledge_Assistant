package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.rag.RetrievalFilter;
import com.rag.springai.insuranceai.domain.shared.TraceId;

import java.util.Objects;

/**
 * {@code filter} (FASE 6, brief section 6/26/27) is optional metadata restrictions from the
 * REST layer - {@link RetrievalFilter#none()} (the two-argument constructor's default) applies
 * no restriction, which is exactly FASE 5's original behaviour, preserved for backward
 * compatibility with a plain {@code {"question": "..."}} request.
 */
public record AskInsuranceKnowledgeCommand(String question, RetrievalFilter filter, TraceId traceId) {

    public AskInsuranceKnowledgeCommand {
        Objects.requireNonNull(question, "question must not be null");
        if (question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        Objects.requireNonNull(filter, "filter must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public AskInsuranceKnowledgeCommand(String question, TraceId traceId) {
        this(question, RetrievalFilter.none(), traceId);
    }
}

package com.rag.springai.insuranceai.domain.security;

import java.util.List;
import java.util.Objects;

/**
 * Result of scanning a piece of text (a user question, or a retrieved chunk) for known prompt
 * injection phrasing (brief section 22/40). {@code matchedPatterns} records which named pattern(s)
 * fired - never the full input text, to keep this record safe to log.
 */
public record PromptInjectionAssessment(boolean detected, List<String> matchedPatterns) {

    public PromptInjectionAssessment {
        Objects.requireNonNull(matchedPatterns, "matchedPatterns must not be null");
        matchedPatterns = List.copyOf(matchedPatterns);
    }

    public static PromptInjectionAssessment clean() {
        return new PromptInjectionAssessment(false, List.of());
    }
}

package com.rag.springai.insuranceai.application.security;

import com.rag.springai.insuranceai.domain.security.PiiAssessment;
import com.rag.springai.insuranceai.domain.security.PromptInjectionAssessment;

import java.util.Objects;

/**
 * Combined result of scanning an incoming question (brief FASE 8 section 22/26). A detected
 * prompt injection attempt blocks the question outright ({@link #blocked()}); detected PII does
 * not - see {@code InputGuardService}'s Javadoc for why the two are treated differently.
 */
public record InputGuardAssessment(PromptInjectionAssessment promptInjection, PiiAssessment pii) {

    public InputGuardAssessment {
        Objects.requireNonNull(promptInjection, "promptInjection must not be null");
        Objects.requireNonNull(pii, "pii must not be null");
    }

    public boolean blocked() {
        return promptInjection.detected();
    }
}

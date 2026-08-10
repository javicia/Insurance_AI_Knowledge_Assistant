package com.rag.springai.insuranceai.domain.aisystem;

import java.util.Objects;

/**
 * Explicit statement of when a human must be involved (brief FASE 9 section 10): this system
 * never decides anything about a person on its own (brief section 12) - it informs, a human
 * always decides on any claim/coverage/eligibility outcome.
 */
public record HumanOversightRequirement(boolean required, String whenRequired, String escalationCondition,
        String decisionResponsibility) {

    public HumanOversightRequirement {
        Objects.requireNonNull(whenRequired, "whenRequired must not be null");
        Objects.requireNonNull(escalationCondition, "escalationCondition must not be null");
        Objects.requireNonNull(decisionResponsibility, "decisionResponsibility must not be null");
    }

    /** The standard oversight statement for this project's one AI system (brief section 49). */
    public static HumanOversightRequirement always(String decisionResponsibility) {
        return new HumanOversightRequirement(true,
                "Any output used to inform a claim, coverage, eligibility, or underwriting decision",
                "Any question or answer touching a specific customer's claim/coverage/eligibility outcome",
                decisionResponsibility);
    }
}

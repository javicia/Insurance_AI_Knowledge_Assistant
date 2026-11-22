package com.rag.springai.insuranceai.application.rag;

/**
 * Why {@code GeneralAiFallbackPolicy} declined to answer a question from general AI knowledge.
 * Recorded on the audit trail and emitted as the {@code reason} tag of {@code
 * rag.general_ai.refused.count}, so "why does this deployment never use its fallback" is a
 * question monitoring can answer without reproducing the request.
 */
public enum GeneralAiFallbackRefusal {

    /** {@code insurance-ai.rag.general-ai-fallback.enabled} is false - the default. */
    DISABLED,

    /**
     * The question asks for a determination about a specific policy, entitlement or amount
     * (coverage, exclusions, deductibles, indemnities, eligibility). A general model answering
     * these would be inventing contractual terms, so the policy refuses regardless of
     * configuration - see {@code GeneralAiFallbackPolicy}.
     */
    CONTRACTUAL_QUESTION,

    /**
     * The question contains detected PII and {@code
     * insurance-ai.rag.general-ai-fallback.allow-questions-with-pii} is false. The grounded path
     * tolerates PII in a question because it stays inside this system; the fallback path would
     * forward it to a third-party provider, which is a different decision with a different legal
     * basis.
     */
    PII_IN_QUESTION,

    /**
     * The configured daily or monthly spend limit has no room for another request's worst-case
     * cost (FASE 29). Refused before the provider is called, not after - see
     * {@code GeneralAiBudgetLedger}.
     */
    BUDGET_EXHAUSTED
}

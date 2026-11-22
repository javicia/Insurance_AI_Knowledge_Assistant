package com.rag.springai.insuranceai.application.rag;

/**
 * Where an answer's content actually came from (FASE 28) - the single structured discriminator
 * the API exposes for "what kind of answer is this", replacing the earlier {@code
 * RagAnswer.blocked} boolean, which could only express two of these four states and had to be
 * read together with {@code grounding.status} to mean anything.
 *
 * <p>This exists because the general-AI fallback introduced an answer that is genuinely useful
 * but is <em>not</em> backed by the company's documentation. Conflating it with {@link
 * #DOCUMENT_GROUNDED} would be the single most damaging thing this feature could do: an employee
 * cannot tell a policy fact from a language model's general knowledge unless the API says so
 * structurally, and "read the disclaimer in the answer text" is not a contract a UI can rely on.
 */
public enum AnswerMode {

    /**
     * Backed by retrieved passages that cleared the evidence-sufficiency bar (see {@code
     * AskInsuranceKnowledgeUseCase#isSufficientEvidence}). The only mode that may carry
     * citations, and the only mode whose content may be treated as what the documentation says.
     */
    DOCUMENT_GROUNDED,

    /**
     * Answered from the language model's own general knowledge because the documentation held no
     * sufficient evidence and {@code GeneralAiFallbackPolicy} permitted it. Carries no citations
     * - never the ones from the insufficient evidence that triggered the fallback, which would
     * be a fabricated provenance claim. Must be rendered with a visible warning.
     */
    GENERAL_AI_UNVERIFIED,

    /**
     * No sufficient documentary evidence, and general AI was not permitted - because it is
     * disabled, or because the policy refused this particular question (see {@code
     * GeneralAiFallbackRefusal}).
     */
    NO_ANSWER,

    /**
     * Rejected by a security guardrail before retrieval or any model call. Never falls back to
     * general AI: a prompt-injection attempt that got answered by an unguarded model would be
     * the attack succeeding, just by a different route.
     */
    BLOCKED
}

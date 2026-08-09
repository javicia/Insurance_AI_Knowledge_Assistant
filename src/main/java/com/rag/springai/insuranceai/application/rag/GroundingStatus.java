package com.rag.springai.insuranceai.application.rag;

/**
 * Whether an answer is backed by retrieved context. {@code NOT_GROUNDED} is only produced by
 * the no-answer path (brief section 11) - the LLM is never called when there is no relevant
 * context, so there is no "the LLM answered without grounding" state to represent here.
 */
public enum GroundingStatus {
    GROUNDED,
    NOT_GROUNDED
}

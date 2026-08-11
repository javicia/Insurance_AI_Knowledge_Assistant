package com.rag.springai.insuranceai.application.rag;

import java.util.List;
import java.util.Objects;

/**
 * The result of {@code AskInsuranceKnowledgeUseCase} - matches the response shape in brief
 * section 9/10/40. Never a Spring AI type: the REST layer serializes this directly.
 *
 * <p><b>{@code piiDetected} (FASE 14 audit remediation):</b> {@code AskInsuranceKnowledgeUseCase}
 * never redacts PII out of a grounded answer (deliberate - see {@code docs/security/PII.md}:
 * redacting risks corrupting a legitimate citation quoted verbatim from a source document). Prior
 * to this field, that detection result was only ever logged server-side, so the API caller had no
 * way to know a returned answer might contain PII copied from a source document. This is
 * transparency, not mitigation: the answer text itself is unchanged either way.
 *
 * <p><b>{@code blocked} (FASE 15 frontend integration):</b> before this field, both {@link
 * #noAnswer} and {@link #blocked} produced an identical {@code grounding.status ==
 * NOT_GROUNDED} with only the free-text {@code answer} message distinguishing "no relevant
 * evidence" from "the question was blocked by the prompt-injection guardrail" - a caller (the
 * Angular frontend, brief section 14) would have had to pattern-match against the exact message
 * string to tell them apart, which is fragile and not a real API contract. {@code blocked} is a
 * genuine structured discriminator, added because it is strictly necessary for the frontend to
 * render two different UX states correctly, not a cosmetic addition.
 */
public record RagAnswer(String answer, List<SourceReference> sources, Grounding grounding, String traceId,
        boolean piiDetected, boolean blocked) {

    private static final String NO_ANSWER_MESSAGE =
            "I do not have sufficient information in the available documentation to answer reliably.";

    private static final String BLOCKED_MESSAGE =
            "This question could not be processed because it appears to attempt to override system "
                    + "instructions.";

    public RagAnswer {
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(sources, "sources must not be null");
        Objects.requireNonNull(grounding, "grounding must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        sources = List.copyOf(sources);
    }

    /**
     * The explicit no-answer response (brief section 11): produced when retrieval finds no
     * chunk above the configured similarity threshold, without ever calling the LLM.
     */
    public static RagAnswer noAnswer(String traceId) {
        return new RagAnswer(NO_ANSWER_MESSAGE, List.of(), new Grounding(GroundingStatus.NOT_GROUNDED), traceId,
                false, false);
    }

    /**
     * Produced when {@code InputGuardService} detects a prompt injection attempt in the question
     * itself (brief FASE 8 section 22) - retrieval and the LLM are never invoked, same as {@link
     * #noAnswer}. Grounding is {@code NOT_GROUNDED}: no grounded answer was produced, blocking is
     * simply a different reason for that than "no relevant evidence".
     */
    public static RagAnswer blocked(String traceId) {
        return new RagAnswer(BLOCKED_MESSAGE, List.of(), new Grounding(GroundingStatus.NOT_GROUNDED), traceId,
                false, true);
    }
}

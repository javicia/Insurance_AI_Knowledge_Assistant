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
 * string to tell them apart, which is fragile and not a real API contract.
 *
 * <p><b>{@code answerMode} (FASE 28):</b> the structured discriminator for "what kind of answer
 * is this", and the field callers should branch on. It replaces that {@code blocked} boolean,
 * which {@link AnswerMode} subsumes exactly while also expressing the general-AI fallback state
 * a boolean could not. {@code grounding} is retained because {@code GroundingStatus} is what the
 * AI Audit trail and {@code EvaluationRunnerService} record and compare - but a two-state boolean
 * would have reintroduced the FASE 15 problem above the moment a third non-grounded state
 * existed.
 */
public record RagAnswer(String answer, List<SourceReference> sources, Grounding grounding, AnswerMode answerMode,
        String traceId, boolean piiDetected) {

    private static final String NO_ANSWER_MESSAGE =
            "I do not have sufficient information in the available documentation to answer reliably.";

    private static final String BLOCKED_MESSAGE =
            "This question could not be processed because it appears to attempt to override system "
                    + "instructions.";

    public RagAnswer {
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(sources, "sources must not be null");
        Objects.requireNonNull(grounding, "grounding must not be null");
        Objects.requireNonNull(answerMode, "answerMode must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        sources = List.copyOf(sources);
        // Enforced here rather than trusted to the call site: a general-AI answer carrying
        // citations would be the exact failure this feature exists to prevent - a fabricated
        // provenance claim, indistinguishable by a reader from a real one. The insufficient
        // evidence that triggered the fallback is not a source for an answer not written from it.
        if (answerMode != AnswerMode.DOCUMENT_GROUNDED && !sources.isEmpty()) {
            throw new IllegalArgumentException("only a DOCUMENT_GROUNDED answer may carry sources, but " + answerMode
                    + " carried " + sources.size());
        }
    }

    /**
     * The explicit no-answer response (brief section 11): produced when retrieval finds no
     * chunk above the configured similarity threshold, without ever calling the LLM.
     */
    public static RagAnswer noAnswer(String traceId) {
        return new RagAnswer(NO_ANSWER_MESSAGE, List.of(), new Grounding(GroundingStatus.NOT_GROUNDED),
                AnswerMode.NO_ANSWER, traceId, false);
    }

    /**
     * Produced when {@code InputGuardService} detects a prompt injection attempt in the question
     * itself (brief FASE 8 section 22) - retrieval and the LLM are never invoked, same as {@link
     * #noAnswer}. Grounding is {@code NOT_GROUNDED}: no grounded answer was produced, blocking is
     * simply a different reason for that than "no relevant evidence".
     */
    public static RagAnswer blocked(String traceId) {
        return new RagAnswer(BLOCKED_MESSAGE, List.of(), new Grounding(GroundingStatus.NOT_GROUNDED),
                AnswerMode.BLOCKED, traceId, false);
    }

    /**
     * An answer written from the model's own general knowledge, because the documentation held
     * no sufficient evidence and {@code GeneralAiFallbackPolicy} permitted the fallback (FASE
     * 28). {@code grounding} is {@code NOT_GROUNDED} - truthfully, since no document grounded it.
     */
    public static RagAnswer generalAi(String answer, String traceId, boolean piiDetected) {
        return new RagAnswer(answer, List.of(), new Grounding(GroundingStatus.NOT_GROUNDED),
                AnswerMode.GENERAL_AI_UNVERIFIED, traceId, piiDetected);
    }
}

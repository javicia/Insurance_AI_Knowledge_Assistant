package com.rag.springai.insuranceai.application.rag;

import java.util.List;
import java.util.Objects;

/**
 * The result of {@code AskInsuranceKnowledgeUseCase} - matches the response shape in brief
 * section 9/10/40. Never a Spring AI type: the REST layer serializes this directly.
 */
public record RagAnswer(String answer, List<SourceReference> sources, Grounding grounding, String traceId) {

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
        return new RagAnswer(NO_ANSWER_MESSAGE, List.of(), new Grounding(GroundingStatus.NOT_GROUNDED), traceId);
    }

    /**
     * Produced when {@code InputGuardService} detects a prompt injection attempt in the question
     * itself (brief FASE 8 section 22) - retrieval and the LLM are never invoked, same as {@link
     * #noAnswer}. Grounding is {@code NOT_GROUNDED}: no grounded answer was produced, blocking is
     * simply a different reason for that than "no relevant evidence".
     */
    public static RagAnswer blocked(String traceId) {
        return new RagAnswer(BLOCKED_MESSAGE, List.of(), new Grounding(GroundingStatus.NOT_GROUNDED), traceId);
    }
}

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
}

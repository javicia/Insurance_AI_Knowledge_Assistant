package com.rag.springai.insuranceai.domain.rag;

import java.util.List;
import java.util.Objects;

/**
 * Input to {@code LlmProvider}. Keeps {@code systemInstructions} (trusted, written by this
 * application) structurally separate from {@code retrievedContextPassages} (untrusted data
 * pulled from documents - brief section 14): a provider adapter must never merge them into a
 * single opaque string before handing them to the vendor SDK, so the distinction survives all
 * the way to the actual API call (e.g. as a system message vs. content embedded in the user
 * message, clearly delimited).
 */
public record LlmPrompt(String systemInstructions, String userQuestion, List<String> retrievedContextPassages) {

    public LlmPrompt {
        Objects.requireNonNull(systemInstructions, "systemInstructions must not be null");
        Objects.requireNonNull(userQuestion, "userQuestion must not be null");
        Objects.requireNonNull(retrievedContextPassages, "retrievedContextPassages must not be null");
        if (userQuestion.isBlank()) {
            throw new IllegalArgumentException("userQuestion must not be blank");
        }
        retrievedContextPassages = List.copyOf(retrievedContextPassages);
    }
}

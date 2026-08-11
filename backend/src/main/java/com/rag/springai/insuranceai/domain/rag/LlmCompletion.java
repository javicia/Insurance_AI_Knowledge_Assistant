package com.rag.springai.insuranceai.domain.rag;

import java.util.Objects;

/**
 * Output of {@code LlmProvider}. Deliberately just the generated text: no vendor-specific
 * response metadata leaks past the port (brief section 6/7).
 */
public record LlmCompletion(String text) {

    public LlmCompletion {
        Objects.requireNonNull(text, "text must not be null");
    }
}

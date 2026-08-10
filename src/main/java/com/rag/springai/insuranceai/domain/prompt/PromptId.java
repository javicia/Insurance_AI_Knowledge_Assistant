package com.rag.springai.insuranceai.domain.prompt;

import java.util.Objects;
import java.util.UUID;

public record PromptId(UUID value) {

    public PromptId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static PromptId generate() {
        return new PromptId(UUID.randomUUID());
    }

    public static PromptId of(String value) {
        return new PromptId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

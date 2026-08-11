package com.rag.springai.insuranceai.domain.aisystem;

import java.util.Objects;
import java.util.UUID;

public record AiSystemId(UUID value) {

    public AiSystemId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static AiSystemId generate() {
        return new AiSystemId(UUID.randomUUID());
    }

    public static AiSystemId of(String value) {
        return new AiSystemId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

package com.rag.springai.insuranceai.application.rag;

import java.util.Objects;

public record Grounding(GroundingStatus status) {

    public Grounding {
        Objects.requireNonNull(status, "status must not be null");
    }
}

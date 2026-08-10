package com.rag.springai.insuranceai.domain.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Correlates a single request across logs, audit events and API responses (brief section 22
 * and 29). Deliberately vendor/framework agnostic: adapters populate it from an incoming
 * header or generate a new one, but the type itself has no dependency on Spring or any web
 * technology.
 */
public record TraceId(String value) {

    public static final String MDC_KEY = "traceId";

    public TraceId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("TraceId value must not be blank");
        }
    }

    public static TraceId generate() {
        return new TraceId(UUID.randomUUID().toString());
    }

    public static TraceId of(String value) {
        return new TraceId(value);
    }
}

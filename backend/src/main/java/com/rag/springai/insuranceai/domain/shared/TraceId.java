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

    /**
     * FASE 25 (Distributed Tracing): deliberately {@code "correlationId"}, not {@code "traceId"}
     * - once real distributed tracing is on the classpath (Micrometer Tracing + OTel), Spring
     * Boot auto-populates MDC keys {@code traceId}/{@code spanId} itself with the real W3C trace/
     * span IDs. Reusing that same key name for this class's own header-propagated business
     * correlation ID (the {@code X-Trace-Id} header - a distinct concept, see this class's own
     * Javadoc) would silently collide: whichever mechanism writes to MDC last would win,
     * non-deterministically shadowing the other in every log line. Kept as a separate field
     * instead - {@code logback-spring.xml} logs both, explicitly labelled, side by side.
     */
    public static final String MDC_KEY = "correlationId";

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

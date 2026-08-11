package com.rag.springai.insuranceai.gateway;

import java.time.Instant;
import java.util.Objects;

/**
 * FASE 23: the gateway's own structured security event, deliberately field-compatible with the
 * backend's {@code com.rag.springai.insuranceai.domain.security.SecurityEvent} (same ECS-inspired
 * field names, same data-minimization contract - see that class's Javadoc) but not the *same*
 * type: the gateway is a separate Maven module/deployable with no dependency on the backend's
 * code, so duplicating this small record here is the honest alternative to either a shared
 * library module (not justified for one record) or a forbidden cross-module dependency.
 *
 * <p>Today the gateway only ever raises {@link GatewaySecurityEventType#RATE_LIMIT_EXCEEDED} -
 * JWT validation failures at the gateway are a defense-in-depth re-check of what the backend
 * already validates and events, so are not duplicated here (see {@code docs/security/SIEM.md}).
 */
public record GatewaySecurityEvent(Instant timestamp, GatewaySecurityEventType type, String traceId, String subject,
        String httpMethod, String path, Integer httpStatus, String reason) {

    public GatewaySecurityEvent {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public static GatewaySecurityEvent now(GatewaySecurityEventType type, String traceId, String subject,
            String httpMethod, String path, Integer httpStatus, String reason) {
        return new GatewaySecurityEvent(Instant.now(), type, traceId, subject, httpMethod, path, httpStatus, reason);
    }
}

package com.rag.springai.insuranceai.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The gateway-side counterpart of the backend's {@code SecurityEventLogger} - same
 * dedicated {@code "security-events"} logger name (so log routing config can treat both
 * uniformly), same hand-built-JSON reasoning (see that class's Javadoc), same honest boundary:
 * structured JSON to stdout only, no SIEM/collector wired up in this PoC (see
 * {@code docs/security/SIEM.md}).
 */
@Component
public class GatewaySecurityEventLogger {

    private static final Logger log = LoggerFactory.getLogger("security-events");

    /** Deliberately a *different* logger than {@link #log} - see the backend
     *  {@code SecurityEventLogger}'s identical field for why. */
    private static final Logger failureLog = LoggerFactory.getLogger(GatewaySecurityEventLogger.class);

    public void log(GatewaySecurityEvent event) {
        try {
            log.warn(toJson(event));
        }
        catch (RuntimeException e) {
            failureLog.warn("Failed to emit security event of type {} - continuing, since security event "
                    + "logging must never break the request it describes", event.type(), e);
        }
    }

    private String toJson(GatewaySecurityEvent event) {
        StringBuilder json = new StringBuilder(256);
        json.append("{");
        appendField(json, "@timestamp", event.timestamp().toString(), true);
        appendField(json, "event.kind", "event", false);
        appendField(json, "event.category", "security", false);
        appendField(json, "event.type", event.type().name(), false);
        appendField(json, "trace.id", event.traceId(), false);
        appendField(json, "service.name", "insurance-ai-gateway", false);
        if (event.subject() != null) {
            appendField(json, "user.id", event.subject(), false);
        }
        if (event.httpMethod() != null) {
            appendField(json, "http.request.method", event.httpMethod(), false);
        }
        if (event.path() != null) {
            appendField(json, "url.path", event.path(), false);
        }
        if (event.httpStatus() != null) {
            json.append(",\"http.response.status_code\":").append(event.httpStatus());
        }
        if (event.reason() != null) {
            appendField(json, "event.reason", event.reason(), false);
        }
        json.append("}");
        return json.toString();
    }

    private void appendField(StringBuilder json, String field, String value, boolean first) {
        if (!first) {
            json.append(",");
        }
        json.append("\"").append(field).append("\":\"").append(escape(value)).append("\"");
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

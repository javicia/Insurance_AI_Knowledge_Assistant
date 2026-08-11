package com.rag.springai.insuranceai.infrastructure.security;

import com.rag.springai.insuranceai.domain.security.SecurityEvent;
import com.rag.springai.insuranceai.ports.outbound.SecurityEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FASE 23: emits one structured JSON line per {@link SecurityEvent} to a dedicated logger
 * (`security-events`), separable in log routing configuration from ordinary application logs.
 *
 * <p><b>The honest boundary, stated once here rather than implied</b>:
 *
 * <pre>
 * application code -&gt; SecurityEvent -&gt; this logger -&gt; stdout (JSON)
 *   -&gt; [not implemented in this PoC] external log collector -&gt; [not implemented] real SIEM
 * </pre>
 *
 * This is "structured security event logging", not "SIEM integration" - no Splunk/Elastic
 * Security/Microsoft Sentinel connection exists. Every log line is genuinely structured,
 * genuinely emitted for every real occurrence of the events below, and genuinely data-minimized
 * (see {@link SecurityEvent}'s Javadoc) - but the collector/SIEM half of the pipeline above is a
 * documented gap (`docs/security/SIEM.md`), never claimed as implemented.
 */
@Component
public class SecurityEventLogger implements SecurityEventPort {

    /** Dedicated logger name - a log-routing config can send `security-events.*` to a different
     *  sink (file, syslog, a future collector) without touching every other class's logger. */
    private static final Logger log = LoggerFactory.getLogger("security-events");

    /** Deliberately a *different* logger than {@link #log} - used only to report a failure of
     *  the security-event sink itself, so that failure is never mistaken for a normal security
     *  event by anything consuming the {@code security-events} stream downstream. */
    private static final Logger failureLog = LoggerFactory.getLogger(SecurityEventLogger.class);

    @Override
    public void log(SecurityEvent event) {
        // This adapter's own contract is "never throws" - callers (SecurityErrorHandler,
        // AuditController, AskInsuranceKnowledgeUseCase) also defensively wrap their call to this
        // port, but that is defense-in-depth, not a reason to skip the same guarantee here: an
        // event-logging failure must never be capable of turning a legitimate request into an
        // unhandled 500 anywhere in the call chain.
        try {
            log.warn(toJson(event));
        }
        catch (RuntimeException e) {
            failureLog.warn("Failed to emit security event of type {} - continuing, since security event "
                    + "logging must never break the request it describes", event.type(), e);
        }
    }

    /**
     * Hand-built JSON, not a library serializer - every field here is already a known-safe type
     * (enum, String already vetted by the caller to exclude secrets/PII, Instant, Integer), so a
     * full serializer would be no safer and adds a dependency this narrowly-scoped class does not
     * need. {@link SecurityEvent}'s own Javadoc is the actual data-minimization contract; this
     * method only formats what it is given.
     */
    private String toJson(SecurityEvent event) {
        StringBuilder json = new StringBuilder(256);
        json.append("{");
        appendField(json, "@timestamp", event.timestamp().toString(), true);
        appendField(json, "event.kind", "event", false);
        appendField(json, "event.category", "security", false);
        appendField(json, "event.type", event.type().name(), false);
        appendField(json, "event.outcome", event.outcome().name(), false);
        appendField(json, "trace.id", event.traceId(), false);
        appendField(json, "service.name", "insurance-ai-backend", false);
        if (event.principalId() != null) {
            appendField(json, "user.id", event.principalId(), false);
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

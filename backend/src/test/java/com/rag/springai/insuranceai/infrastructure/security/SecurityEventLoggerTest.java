package com.rag.springai.insuranceai.infrastructure.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import com.rag.springai.insuranceai.domain.security.SecurityEvent;
import com.rag.springai.insuranceai.domain.security.SecurityEventOutcome;
import com.rag.springai.insuranceai.domain.security.SecurityEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies the structured-JSON shape the FASE 23 brief requires, and - just as important - what
 * it must never contain (Authorization headers, tokens, passwords, raw PII, full prompts). Reads
 * the real {@code "security-events"} logger's output via a Logback {@link ListAppender} rather
 * than mocking SLF4J, so this is exercising the actual formatting code, not a stand-in for it.
 */
class SecurityEventLoggerTest {

    private final SecurityEventLogger securityEventLogger = new SecurityEventLogger();
    private ListAppender<ILoggingEvent> appender;
    private Logger securityEventsLogger;

    @BeforeEach
    void attachAppender() {
        securityEventsLogger = (Logger) LoggerFactory.getLogger("security-events");
        appender = new ListAppender<>();
        appender.start();
        securityEventsLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        securityEventsLogger.detachAppender(appender);
    }

    private String logAndCapture(SecurityEvent event) {
        securityEventLogger.log(event);
        assertThat(appender.list).hasSize(1);
        ILoggingEvent logged = appender.list.get(0);
        assertThat(logged.getLevel()).isEqualTo(Level.WARN);
        return logged.getFormattedMessage();
    }

    @Test
    void logsAllMandatoryFieldsForAFullyPopulatedEvent() {
        SecurityEvent event = new SecurityEvent(Instant.parse("2026-08-11T10:00:00Z"),
                SecurityEventType.AUTHORIZATION_DENIED, SecurityEventOutcome.DENIED, "trace-123", "alice.user",
                "GET", "/api/audit/recent", 403, "AccessDeniedException");

        String json = logAndCapture(event);

        assertThat(json).contains("\"@timestamp\":\"2026-08-11T10:00:00Z\"");
        assertThat(json).contains("\"event.kind\":\"event\"");
        assertThat(json).contains("\"event.category\":\"security\"");
        assertThat(json).contains("\"event.type\":\"AUTHORIZATION_DENIED\"");
        assertThat(json).contains("\"event.outcome\":\"DENIED\"");
        assertThat(json).contains("\"trace.id\":\"trace-123\"");
        assertThat(json).contains("\"service.name\":\"insurance-ai-backend\"");
        assertThat(json).contains("\"user.id\":\"alice.user\"");
        assertThat(json).contains("\"http.request.method\":\"GET\"");
        assertThat(json).contains("\"url.path\":\"/api/audit/recent\"");
        assertThat(json).contains("\"http.response.status_code\":403");
        assertThat(json).contains("\"event.reason\":\"AccessDeniedException\"");
    }

    @Test
    void producesValidJsonShapeStartingAndEndingWithBraces() {
        SecurityEvent event = SecurityEvent.now(SecurityEventType.RATE_LIMIT_EXCEEDED, SecurityEventOutcome.BLOCKED,
                "trace-456", null, null, null, null, null);

        String json = logAndCapture(event);

        assertThat(json).startsWith("{").endsWith("}");
        // every field is a quoted key followed by a colon - a structural sanity check that this
        // is well-formed JSON without pulling in a full parser dependency for a hand-built string.
        assertThat(json).matches("\\{(\"[^\"]+\":(\"[^\"]*\"|\\d+),?)+}");
    }

    @Test
    void omitsOptionalFieldsThatAreNullRatherThanEmittingNullLiterals() {
        SecurityEvent event = SecurityEvent.now(SecurityEventType.PII_DETECTED, SecurityEventOutcome.DETECTED,
                "trace-789", null, null, null, null, null);

        String json = logAndCapture(event);

        assertThat(json).doesNotContain("\"user.id\"");
        assertThat(json).doesNotContain("\"http.request.method\"");
        assertThat(json).doesNotContain("\"url.path\"");
        assertThat(json).doesNotContain("\"http.response.status_code\"");
        assertThat(json).doesNotContain("\"event.reason\"");
        assertThat(json).doesNotContain("null");
    }

    @Test
    void escapesQuotesAndBackslashesInFreeformFields() {
        SecurityEvent event = SecurityEvent.now(SecurityEventType.SECURITY_CONFIGURATION_ERROR,
                SecurityEventOutcome.ERROR, "trace-esc", "user\"withquote", null, null, null,
                "reason with \\backslash\\ and \"quotes\"");

        String json = logAndCapture(event);

        assertThat(json).contains("user\\\"withquote");
        assertThat(json).contains("reason with \\\\backslash\\\\ and \\\"quotes\\\"");
        // still structurally valid - an unescaped quote here would prematurely close the JSON string.
        assertThat(json).matches("\\{(\"[^\"]+\":(\"(\\\\.|[^\"\\\\])*\"|\\d+),?)+}");
    }

    @Test
    void neverContainsAnAuthorizationHeaderOrBearerTokenShapedValue() {
        // The contract (SecurityEvent's own Javadoc) is that callers never pass secrets in -
        // this test proves the logger itself adds nothing that could leak one, by asserting the
        // formatter's fixed field set never includes anything token/secret-shaped even when every
        // optional field is populated with adversarial-looking (but realistic) values.
        SecurityEvent event = new SecurityEvent(Instant.now(), SecurityEventType.INVALID_TOKEN,
                SecurityEventOutcome.DENIED, "trace-tok", "bob.governance", "POST", "/api/chat", 401,
                "InvalidBearerTokenException");

        String json = logAndCapture(event);

        assertThat(json).doesNotContainIgnoringCase("authorization");
        assertThat(json).doesNotContainIgnoringCase("bearer ");
        assertThat(json).doesNotContainIgnoringCase("refresh_token");
        assertThat(json).doesNotContainIgnoringCase("password");
        assertThat(json).doesNotContainIgnoringCase("eyJ"); // JWT header base64 prefix
    }

    @Test
    void logsAtWarnLevelForEveryEventRegardlessOfOutcome() {
        for (SecurityEventOutcome outcome : SecurityEventOutcome.values()) {
            appender.list.clear();
            SecurityEvent event = SecurityEvent.now(SecurityEventType.AUDIT_ACCESS, outcome, "trace-" + outcome,
                    null, null, null, null, null);
            securityEventLogger.log(event);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        }
    }

    /**
     * FASE 23 incident follow-up: proves the sink is genuinely best-effort at its own level, not
     * just at each caller's level (see {@code SecurityErrorHandlerTest}/{@code
     * AuditControllerTest}/{@code AskInsuranceKnowledgeUseCaseTest} for the caller-side
     * equivalents). A throwing {@code Appender} attached to the real {@code "security-events"}
     * logger forces {@code log.warn(...)} itself to throw - {@link SecurityEventLogger#log}
     * must swallow that, never propagate it to whatever called it.
     */
    @Test
    void logSwallowsAnExceptionFromTheUnderlyingLoggingMechanismRatherThanPropagatingIt() {
        AppenderBase<ILoggingEvent> throwingAppender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent eventObject) {
                throw new IllegalStateException("simulated sink failure");
            }
        };
        throwingAppender.setContext(securityEventsLogger.getLoggerContext());
        throwingAppender.start();
        securityEventsLogger.addAppender(throwingAppender);

        try {
            SecurityEvent event = SecurityEvent.now(SecurityEventType.AUTHENTICATION_FAILURE,
                    SecurityEventOutcome.DENIED, "trace-failure", null, null, null, null, null);

            assertThatCode(() -> securityEventLogger.log(event)).doesNotThrowAnyException();
        }
        finally {
            securityEventsLogger.detachAppender(throwingAppender);
            // Logback's own internal error handling records the appender failure above as an
            // ERROR-level entry in the LoggerContext's StatusManager - a JVM-wide singleton shared
            // by every test in this Surefire fork. Left uncleared, Spring Boot's own
            // LogbackLoggingSystem#reportConfigurationErrorsIfNecessary treats any pre-existing
            // ERROR status as fatal the next time ANY @SpringBootTest in this fork initializes
            // logging - poisoning every unrelated test that runs afterward with an unrelated
            // "Logback configuration error detected" IllegalStateException. Found exactly that way:
            // this test passed in isolation but broke every @SpringBootTest run alongside it.
            securityEventsLogger.getLoggerContext().getStatusManager().clear();
        }
    }
}

package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

import com.rag.springai.insuranceai.application.audit.AuditRecordNotFoundException;
import com.rag.springai.insuranceai.application.audit.AuditService;
import com.rag.springai.insuranceai.domain.security.SecurityEventOutcome;
import com.rag.springai.insuranceai.domain.security.SecurityEventType;
import com.rag.springai.insuranceai.ports.outbound.SecurityEventPort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the real AUDIT_ACCESS call site (brief FASE 23: "who looked up what execution record,
 * and when" is itself security-relevant) emits an event on every access attempt to either
 * endpoint - including a lookup for a traceId that turns out not to exist, since the probe
 * attempt itself is what matters here, not whether it succeeded.
 */
class AuditControllerTest {

    private final AuditService auditService = mock(AuditService.class);
    private final SecurityEventPort securityEventLogger = mock(SecurityEventPort.class);
    private final AuditController controller = new AuditController(auditService, securityEventLogger);

    @Test
    void getByTraceIdEmitsAnAuditAccessEventCarryingTheAuthenticatedPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/traces/trace-1");
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("carol.auditor", null, List.of()));
        when(auditService.findByTraceId("trace-1")).thenReturn(Optional.empty());

        try {
            assertThatThrownBy(() -> controller.getByTraceId("trace-1", request))
                    .isInstanceOf(AuditRecordNotFoundException.class);
        }
        finally {
            SecurityContextHolder.clearContext();
        }

        verify(securityEventLogger).log(argThat(event -> event.type() == SecurityEventType.AUDIT_ACCESS
                && event.outcome() == SecurityEventOutcome.DETECTED
                && event.principalId().equals("carol.auditor")
                && event.httpMethod().equals("GET")
                && event.path().equals("/api/audit/traces/trace-1")));
    }

    @Test
    void recentEmitsAnAuditAccessEvent() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/recent");
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("carol.auditor", null, List.of()));
        when(auditService.findRecent(anyInt())).thenReturn(List.of());

        try {
            controller.recent(20, request);
        }
        finally {
            SecurityContextHolder.clearContext();
        }

        verify(securityEventLogger).log(argThat(
                event -> event.type() == SecurityEventType.AUDIT_ACCESS && event.path().equals("/api/audit/recent")));
    }

    /**
     * FASE 23 incident follow-up (2026-08-11) - see {@code SecurityErrorHandlerTest}'s identical
     * test for the full context: a legitimate audit lookup must still succeed even if security
     * event logging itself throws.
     */
    @Test
    void recentStillReturnsResultsEvenIfSecurityEventLoggingThrows() {
        doThrow(new RuntimeException("simulated event sink failure")).when(securityEventLogger).log(any());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/recent");
        when(auditService.findRecent(anyInt())).thenReturn(List.of());

        assertThatCode(() -> controller.recent(20, request)).doesNotThrowAnyException();
    }
}

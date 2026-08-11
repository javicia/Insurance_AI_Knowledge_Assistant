package com.rag.springai.insuranceai.infrastructure.security;

import com.rag.springai.insuranceai.domain.security.SecurityEventOutcome;
import com.rag.springai.insuranceai.domain.security.SecurityEventType;
import com.rag.springai.insuranceai.ports.outbound.SecurityEventPort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Proves the two real 401/403 call sites (brief FASE 23 requirement: "authentication failure",
 * "authorization failure" must be real, not just {@link SecurityEventLogger} tested in
 * isolation) actually emit a {@link com.rag.springai.insuranceai.domain.security.SecurityEvent}
 * through the port - not merely that {@code SecurityErrorHandler} still returns the right HTTP
 * status (already covered elsewhere).
 */
class SecurityErrorHandlerTest {

    private final SecurityEventPort securityEventLogger = mock(SecurityEventPort.class);
    private final SecurityErrorHandler handler = new SecurityErrorHandler(securityEventLogger);

    @Test
    void commenceEmitsAnAuthenticationFailureEvent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/recent");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(request, response, new AuthenticationCredentialsNotFoundException("no credentials"));

        assertThat(response.getStatus()).isEqualTo(401);
        verify(securityEventLogger).log(argThat(event -> event.type() == SecurityEventType.AUTHENTICATION_FAILURE
                && event.outcome() == SecurityEventOutcome.DENIED
                && event.httpMethod().equals("GET")
                && event.path().equals("/api/audit/recent")
                && event.httpStatus() == 401
                && event.principalId() == null
                // reason is the exception's class name (a bounded category), never getMessage()
                && event.reason().equals("AuthenticationCredentialsNotFoundException")));
    }

    @Test
    void handleEmitsAnAuthorizationDeniedEventCarryingTheAuthenticatedPrincipal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/governance/ai-systems");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("alice.user", null, List.of()));

        try {
            handler.handle(request, response, new AccessDeniedException("missing authority"));
        }
        finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(response.getStatus()).isEqualTo(403);
        verify(securityEventLogger).log(argThat(event -> event.type() == SecurityEventType.AUTHORIZATION_DENIED
                && event.outcome() == SecurityEventOutcome.DENIED
                && event.principalId().equals("alice.user")
                && event.httpMethod().equals("POST")
                && event.path().equals("/api/governance/ai-systems")
                && event.httpStatus() == 403
                && event.reason().equals("AccessDeniedException")));
    }

    /**
     * FASE 23 incident follow-up (2026-08-11): a real full-suite run surfaced a legitimate
     * question - "what happens if {@code securityEventLogger.log(...)} itself throws?" -
     * alongside an unrelated 500 (root-caused separately to Testcontainers Postgres reuse/test
     * data pollution, not to this code). The answer must always be "the correct security response
     * is still returned" - security event logging is best-effort observability, never a
     * precondition for answering a real 401/403.
     */
    @Test
    void commenceStillReturns401EvenIfSecurityEventLoggingThrows() throws Exception {
        doThrow(new RuntimeException("simulated event sink failure")).when(securityEventLogger).log(any());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/recent");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatCode(() -> handler.commence(request, response,
                new AuthenticationCredentialsNotFoundException("no credentials"))).doesNotThrowAnyException();

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void handleStillReturns403EvenIfSecurityEventLoggingThrows() throws Exception {
        doThrow(new RuntimeException("simulated event sink failure")).when(securityEventLogger).log(any());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/governance/ai-systems");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            assertThatCode(() -> handler.handle(request, response, new AccessDeniedException("missing authority")))
                    .doesNotThrowAnyException();
        }
        finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(response.getStatus()).isEqualTo(403);
    }
}

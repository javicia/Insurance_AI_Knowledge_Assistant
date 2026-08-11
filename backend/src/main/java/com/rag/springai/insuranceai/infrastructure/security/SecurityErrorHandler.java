package com.rag.springai.insuranceai.infrastructure.security;

import java.io.IOException;

import com.rag.springai.insuranceai.adapters.inbound.rest.ErrorResponse;
import com.rag.springai.insuranceai.domain.security.SecurityEvent;
import com.rag.springai.insuranceai.domain.security.SecurityEventOutcome;
import com.rag.springai.insuranceai.domain.security.SecurityEventType;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import com.rag.springai.insuranceai.ports.outbound.SecurityEventPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Makes a 401 (no/invalid/expired token) or 403 (valid token, missing authority) look exactly
 * like every other API error ({@link ErrorResponse}: {@code code}/{@code message}/{@code
 * traceId}) instead of Spring Security's own default plain-text/WWW-Authenticate-only response -
 * consistent with {@code GlobalExceptionHandler}'s contract, and never leaking which specific
 * validation step failed (bad signature vs. expired vs. wrong issuer all look identical to the
 * caller - that detail is only in the server-side log line, matching data-minimization practice
 * already established for {@code GlobalExceptionHandler}).
 *
 * <p>Deliberately builds its JSON body by hand rather than autowiring an {@code ObjectMapper}:
 * this handler is constructed very early during {@code SecurityFilterChain} bean creation (before
 * {@code JacksonAutoConfiguration} has necessarily finished registering its own bean in every
 * ordering), and every field here (a fixed code, a fixed message, a UUID-shaped trace id) is
 * already known not to require escaping - a real serializer would be over-engineering for three
 * constant-shaped strings.
 */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorHandler.class);

    private final SecurityEventPort securityEventLogger;

    public SecurityErrorHandler(SecurityEventPort securityEventLogger) {
        this.securityEventLogger = securityEventLogger;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        log.warn("Authentication failed for {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getMessage());
        String traceId = currentTraceId();
        // reason is the exception's class name, a fixed/bounded category (e.g. "BadCredentialsException",
        // "InvalidBearerTokenException") - never exception.getMessage(), which can echo back
        // caller-supplied header/token content.
        logSecurityEventSafely(SecurityEvent.now(SecurityEventType.AUTHENTICATION_FAILURE,
                SecurityEventOutcome.DENIED, traceId, null, request.getMethod(), request.getRequestURI(),
                HttpStatus.UNAUTHORIZED.value(), exception.getClass().getSimpleName()));
        write(response, traceId, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication is required and has failed or has not yet been provided.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        log.warn("Access denied for {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        String traceId = currentTraceId();
        logSecurityEventSafely(SecurityEvent.now(SecurityEventType.AUTHORIZATION_DENIED,
                SecurityEventOutcome.DENIED, traceId, currentPrincipalId(), request.getMethod(),
                request.getRequestURI(), HttpStatus.FORBIDDEN.value(), exception.getClass().getSimpleName()));
        write(response, traceId, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "The authenticated principal does not have the required authority for this resource.");
    }

    /**
     * Security event logging is best-effort observability, never a precondition for answering a
     * real 401/403 - a caller must always get the correct security response even if the event
     * sink itself is broken. Any exception from {@link SecurityEventPort#log} (today's
     * implementation shouldn't throw, but the port contract doesn't guarantee that for every
     * future implementation) is caught here and demoted to a plain {@code WARN} on this class's
     * own logger, never the {@code "security-events"} logger - swallowing it there too would risk
     * masking a real sink failure as a normal security event.
     */
    private void logSecurityEventSafely(SecurityEvent event) {
        try {
            securityEventLogger.log(event);
        }
        catch (RuntimeException e) {
            log.warn("Failed to emit security event of type {} - continuing, since security event logging must "
                    + "never break the request it describes", event.type(), e);
        }
    }

    private String currentPrincipalId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }

    private String currentTraceId() {
        String traceId = MDC.get(TraceId.MDC_KEY);
        return traceId != null ? traceId : TraceId.generate().value();
    }

    private void write(HttpServletResponse response, String traceId, HttpStatus status, String code, String message)
            throws IOException {
        ErrorResponse errorResponse = new ErrorResponse(code, message, traceId);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter()
                .write("{\"code\":\"%s\",\"message\":\"%s\",\"traceId\":\"%s\"}"
                        .formatted(errorResponse.code(), errorResponse.message(), errorResponse.traceId()));
    }
}

package com.rag.springai.insuranceai.infrastructure.security;

import java.io.IOException;

import com.rag.springai.insuranceai.adapters.inbound.rest.ErrorResponse;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
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

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        log.warn("Authentication failed for {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getMessage());
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication is required and has failed or has not yet been provided.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        log.warn("Access denied for {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        write(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "The authenticated principal does not have the required authority for this resource.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        String traceId = MDC.get(TraceId.MDC_KEY);
        if (traceId == null) {
            traceId = TraceId.generate().value();
        }
        ErrorResponse errorResponse = new ErrorResponse(code, message, traceId);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter()
                .write("{\"code\":\"%s\",\"message\":\"%s\",\"traceId\":\"%s\"}"
                        .formatted(errorResponse.code(), errorResponse.message(), errorResponse.traceId()));
    }
}

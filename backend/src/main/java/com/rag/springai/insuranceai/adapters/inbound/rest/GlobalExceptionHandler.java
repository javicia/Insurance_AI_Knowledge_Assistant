package com.rag.springai.insuranceai.adapters.inbound.rest;

import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import com.rag.springai.insuranceai.domain.shared.exception.DomainException;
import com.rag.springai.insuranceai.domain.shared.exception.InfrastructureException;
import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates the three exception hierarchies (domain, application, infrastructure — brief
 * section 52) into a consistent {@link ErrorResponse}. Never exposes a stack trace to the
 * client; full details are logged server-side only.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * An unmapped path (including under {@code /api/**} - the backend is an API-only deployable
     * since the FASE 16 frontend/backend separation, see
     * {@code docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md}) falls through every
     * {@code @RestController} mapping and Spring Boot's default static-resource handler (which
     * has nothing to serve - this image ships no static content), so Spring's resource resolution
     * throws this exception instead - without this handler it would fall through to the generic
     * {@link #handleUnexpectedException} and incorrectly answer with {@code 500} instead of the
     * semantically correct {@code 404} (caught by {@code ApiRoutingIntegrationTest}).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException exception) {
        log.warn("No resource found for {} {}", exception.getHttpMethod(), exception.getResourcePath());
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource was not found.");
    }

    /**
     * FASE 15 (Angular SPA integration, found alongside the {@link NoResourceFoundException} fix
     * above via manual E2E verification of every real API route): a real, mapped {@code /api/**}
     * path called with the wrong HTTP method (e.g. {@code GET /api/evaluation/runs}, which only
     * supports {@code POST}) throws this exception - without this handler it falls through to the
     * generic {@link #handleUnexpectedException} and incorrectly answers with {@code 500} instead
     * of the semantically correct {@code 405}.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException exception) {
        log.warn("Method not supported: {}", exception.getMessage());
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "The requested method is not supported "
                + "for this resource.");
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ErrorResponse> handleDomainException(DomainException exception) {
        log.warn("Domain rule violation: {}", exception.errorCode());
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, exception.errorCode(), exception.getMessage());
    }

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ErrorResponse> handleApplicationException(ApplicationException exception) {
        log.warn("Use case could not be completed: {}", exception.errorCode());
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, exception.errorCode(), exception.getMessage());
    }

    @ExceptionHandler(PermanentProcessingException.class)
    ResponseEntity<ErrorResponse> handlePermanentProcessingException(PermanentProcessingException exception) {
        log.error("Permanent infrastructure failure (not retry-safe): {}", exception.errorCode(), exception);
        return respond(HttpStatus.BAD_GATEWAY, exception.errorCode(), exception.getMessage());
    }

    @ExceptionHandler(InfrastructureException.class)
    ResponseEntity<ErrorResponse> handleInfrastructureException(InfrastructureException exception) {
        log.error("Infrastructure failure: {}", exception.errorCode(), exception);
        return respond(HttpStatus.SERVICE_UNAVAILABLE, exception.errorCode(), exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("Unexpected error", exception);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.");
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message) {
        String traceId = MDC.get(TraceId.MDC_KEY);
        if (traceId == null) {
            traceId = TraceId.generate().value();
        }
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, traceId));
    }
}

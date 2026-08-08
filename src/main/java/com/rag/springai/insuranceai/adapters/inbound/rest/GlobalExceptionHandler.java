package com.rag.springai.insuranceai.adapters.inbound.rest;

import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import com.rag.springai.insuranceai.domain.shared.exception.DomainException;
import com.rag.springai.insuranceai.domain.shared.exception.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates the three exception hierarchies (domain, application, infrastructure — brief
 * section 52) into a consistent {@link ErrorResponse}. Never exposes a stack trace to the
 * client; full details are logged server-side only.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

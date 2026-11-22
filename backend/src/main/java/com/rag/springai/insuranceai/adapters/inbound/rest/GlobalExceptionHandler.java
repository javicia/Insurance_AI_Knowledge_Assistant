package com.rag.springai.insuranceai.adapters.inbound.rest;

import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import com.rag.springai.insuranceai.domain.shared.exception.DomainException;
import com.rag.springai.insuranceai.domain.shared.exception.InfrastructureException;
import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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

    /**
     * A malformed or incomplete JSON body (e.g. missing a required primitive field, invalid
     * syntax) throws this exception during request deserialization, before any
     * {@code @RestController} method body runs - found via the FASE 23 authorization-matrix E2E
     * verification (an intentionally minimal {@code {}} body against
     * {@code POST /api/governance/ai-systems} returned {@code 500} instead of {@code 400}).
     * Without this handler it falls through to {@link #handleUnexpectedException}, which is wrong
     * both semantically (this is a client error, not a server fault) and for API robustness
     * (brief FASE 31's concern, fixed here on discovery rather than deferred).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException exception) {
        log.warn("Malformed request body: {}", exception.getMessage());
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY", "The request body could not be parsed.");
    }

    /** Same client-error reasoning as {@link #handleHttpMessageNotReadableException} - a
     *  bean-validation failure ({@code @Valid}) is a {@code 400}, not the generic {@code 500}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException exception) {
        log.warn("Request validation failed: {}", exception.getMessage());
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request did not pass validation.");
    }

    /**
     * FASE 25 E2E finding (same client-error-answered-as-500 family as the three handlers above,
     * found the same way - by actually calling the endpoint rather than by inspection): a
     * multipart/form-data request missing a required {@code @RequestParam} (e.g.
     * {@code POST /api/documents} without {@code name}) threw
     * {@link MissingServletRequestParameterException}, which fell through to
     * {@link #handleUnexpectedException} and answered {@code 500}. A caller omitting a required
     * parameter is unambiguously a client error, and answering {@code 500} both misleads the
     * caller (implying a server fault they should retry) and pollutes real server-fault alerting.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException exception) {
        log.warn("Missing required request parameter: {}", exception.getParameterName());
        return respond(HttpStatus.BAD_REQUEST, "MISSING_REQUEST_PARAMETER",
                "A required request parameter is missing.");
    }

    /**
     * Same client-error reasoning as {@link #handleMissingServletRequestParameterException}, found
     * during the same FASE 25 E2E pass: a parameter present but unconvertible to its declared type
     * (most realistically an invalid enum constant, e.g. {@code type=NOT_A_DOCUMENT_TYPE} against
     * {@code POST /api/documents}) throws this instead of the missing-parameter exception, and was
     * likewise answered as {@code 500}. Deliberately does not echo the offending value back in the
     * response body - it is attacker-controlled input, and this project's error contract never
     * reflects raw request content (see {@link ErrorResponse}); the parameter *name* alone is
     * enough for a caller to fix their request, and the value is available server-side in the log.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException exception) {
        log.warn("Request parameter '{}' could not be converted to {}", exception.getName(),
                exception.getRequiredType() != null ? exception.getRequiredType().getSimpleName() : "its declared type");
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_PARAMETER",
                "A request parameter has an invalid value.");
    }

    /**
     * Same client-error reasoning again: a {@code multipart/form-data} endpoint called without the
     * required file part (or with a non-multipart body) is a malformed client request, not a
     * server fault.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ErrorResponse> handleMissingServletRequestPartException(
            MissingServletRequestPartException exception) {
        log.warn("Missing required multipart request part: {}", exception.getRequestPartName());
        return respond(HttpStatus.BAD_REQUEST, "MISSING_REQUEST_PART", "A required request part is missing.");
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

    /**
     * FASE 26 resilience finding, measured by actually stopping PostgreSQL and calling the API: a
     * database outage surfaced as a generic {@code 500 INTERNAL_ERROR}. Spring translates a failed
     * connection acquisition into {@link DataAccessResourceFailureException} (or
     * {@link CannotCreateTransactionException} when it happens while starting a transaction),
     * neither of which extends this project's own {@link InfrastructureException}, so both fell
     * through to {@link #handleUnexpectedException}.
     *
     * <p>{@code 503} is the semantically correct answer and is materially different for a caller:
     * {@code 500} says "this request is broken, do not bother retrying", while {@code 503} says
     * "the service is temporarily unavailable" - which is exactly the situation, and is what makes
     * a client's retry/backoff and an upstream load balancer's health logic behave sensibly.
     *
     * <p>Deliberately narrow: only these two resource-failure types are mapped. A
     * {@code DataIntegrityViolationException}, for instance, is a data/logic fault and must NOT be
     * reported as a transient outage.
     */
    @ExceptionHandler({ DataAccessResourceFailureException.class, CannotCreateTransactionException.class })
    ResponseEntity<ErrorResponse> handleDatabaseUnavailable(Exception exception) {
        log.error("Database unavailable", exception);
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                "A required backing service is temporarily unavailable.");
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

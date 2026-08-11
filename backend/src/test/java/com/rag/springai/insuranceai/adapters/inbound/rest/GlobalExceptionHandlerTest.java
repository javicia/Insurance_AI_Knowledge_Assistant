package com.rag.springai.insuranceai.adapters.inbound.rest;

import com.rag.springai.insuranceai.domain.shared.exception.InfrastructureException;
import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import com.rag.springai.insuranceai.domain.shared.exception.DomainException;
import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Verifies the exception-to-HTTP mapping in isolation, without a business controller: this is
 * cross-cutting infrastructure required by brief section 52, exercised here with a throwaway
 * stub controller rather than a real endpoint (none exist yet in FASE 1).
 */
class GlobalExceptionHandlerTest {

    @RestController
    static class StubController {

        @GetMapping("/domain-error")
        void domainError() {
            throw new StubDomainException();
        }

        @GetMapping("/application-error")
        void applicationError() {
            throw new StubApplicationException();
        }

        @GetMapping("/infrastructure-error")
        void infrastructureError() {
            throw new StubInfrastructureException();
        }

        @GetMapping("/permanent-processing-error")
        void permanentProcessingError() {
            throw new PermanentProcessingException("STUB_PERMANENT_ERROR", "stub permanent failure");
        }

        @GetMapping("/unexpected-error")
        void unexpectedError() {
            throw new IllegalStateException("boom - internal detail that must never reach the client");
        }

        @GetMapping("/no-resource-found")
        void noResourceFound() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "api/this-endpoint-does-not-exist", "/**");
        }

        @org.springframework.web.bind.annotation.PostMapping("/post-only-endpoint")
        void postOnlyEndpoint() {
            // exists only so MockMvc's real HandlerMapping rejects a GET to it below
        }

        @org.springframework.web.bind.annotation.PostMapping("/malformed-body-endpoint")
        void malformedBodyEndpoint(
                @org.springframework.web.bind.annotation.RequestBody StubRequestBody body) {
            // exists only so a body Jackson cannot deserialize throws HttpMessageNotReadableException
        }

        @org.springframework.web.bind.annotation.PostMapping("/validated-body-endpoint")
        void validatedBodyEndpoint(
                @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody StubValidatedBody body) {
            // exists only so a body failing bean validation throws MethodArgumentNotValidException
        }
    }

    record StubRequestBody(boolean requiredFlag) {
    }

    record StubValidatedBody(@jakarta.validation.constraints.NotNull String requiredField) {
    }

    static class StubDomainException extends DomainException {
        StubDomainException() {
            super("STUB_DOMAIN_ERROR", "stub domain failure");
        }
    }

    static class StubApplicationException extends ApplicationException {
        StubApplicationException() {
            super("STUB_APPLICATION_ERROR", "stub application failure");
        }
    }

    static class StubInfrastructureException extends InfrastructureException {
        StubInfrastructureException() {
            super("STUB_INFRASTRUCTURE_ERROR", "stub infrastructure failure");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new StubController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void shouldMapDomainExceptionTo422WithErrorCode() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/domain-error")).andReturn().getResponse();

        assertEquals(422, response.getStatus());
        assertTrue(response.getContentAsString().contains("STUB_DOMAIN_ERROR"));
        assertTrue(response.getContentAsString().contains("\"traceId\""));
    }

    @Test
    void shouldMapApplicationExceptionTo422WithErrorCode() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/application-error")).andReturn().getResponse();

        assertEquals(422, response.getStatus());
        assertTrue(response.getContentAsString().contains("STUB_APPLICATION_ERROR"));
    }

    @Test
    void shouldMapInfrastructureExceptionTo503WithErrorCode() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/infrastructure-error")).andReturn().getResponse();

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("STUB_INFRASTRUCTURE_ERROR"));
    }

    @Test
    void shouldMapPermanentProcessingExceptionTo502WithErrorCode() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/permanent-processing-error")).andReturn()
                .getResponse();

        assertEquals(502, response.getStatus());
        assertTrue(response.getContentAsString().contains("STUB_PERMANENT_ERROR"));
    }

    @Test
    void shouldMapUnexpectedExceptionTo500WithoutLeakingInternalDetails() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/unexpected-error")).andReturn().getResponse();

        assertEquals(500, response.getStatus());
        assertTrue(response.getContentAsString().contains("INTERNAL_ERROR"));
        assertTrue(response.getContentAsString().contains("An unexpected error occurred."));
        assertTrue(response.getContentAsString().contains("boom") == false,
                "the internal exception message must never be exposed to the client");
    }

    @Test
    void shouldMapNoResourceFoundExceptionTo404NotTheGeneric500() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/no-resource-found")).andReturn().getResponse();

        assertEquals(404, response.getStatus());
        assertTrue(response.getContentAsString().contains("NOT_FOUND"));
    }

    @Test
    void shouldMapHttpRequestMethodNotSupportedExceptionTo405NotTheGeneric500() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/post-only-endpoint")).andReturn().getResponse();

        assertEquals(405, response.getStatus());
        assertTrue(response.getContentAsString().contains("METHOD_NOT_ALLOWED"));
    }

    @Test
    void shouldMapHttpMessageNotReadableExceptionTo400NotTheGeneric500() throws Exception {
        MockHttpServletResponse response = mockMvc
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/malformed-body-endpoint")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse();

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("MALFORMED_REQUEST_BODY"));
    }

    @Test
    void shouldMapMethodArgumentNotValidExceptionTo400NotTheGeneric500() throws Exception {
        MockHttpServletResponse response = mockMvc
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/validated-body-endpoint")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse();

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("VALIDATION_FAILED"));
    }

    @Test
    void shouldPropagateTraceIdAlreadyPresentInMdc() throws Exception {
        TraceId traceId = TraceId.generate();
        MDC.put(TraceId.MDC_KEY, traceId.value());
        try {
            MockHttpServletResponse response = mockMvc.perform(get("/domain-error")).andReturn().getResponse();
            assertTrue(response.getContentAsString().contains(traceId.value()));
        } finally {
            MDC.remove(TraceId.MDC_KEY);
        }
    }
}

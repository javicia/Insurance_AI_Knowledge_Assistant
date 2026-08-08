package com.rag.springai.insuranceai.adapters.inbound.rest;

import com.rag.springai.insuranceai.adapters.shared.exception.InfrastructureException;
import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import com.rag.springai.insuranceai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

        @GetMapping("/unexpected-error")
        void unexpectedError() {
            throw new IllegalStateException("boom - internal detail that must never reach the client");
        }
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
    void shouldMapUnexpectedExceptionTo500WithoutLeakingInternalDetails() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/unexpected-error")).andReturn().getResponse();

        assertEquals(500, response.getStatus());
        assertTrue(response.getContentAsString().contains("INTERNAL_ERROR"));
        assertTrue(response.getContentAsString().contains("An unexpected error occurred."));
        assertTrue(response.getContentAsString().contains("boom") == false,
                "the internal exception message must never be exposed to the client");
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

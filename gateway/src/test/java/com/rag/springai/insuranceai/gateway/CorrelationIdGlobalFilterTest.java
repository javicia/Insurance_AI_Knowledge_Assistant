package com.rag.springai.insuranceai.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void generatesATraceIdWhenTheClientSuppliedNone() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/chat").build());

        String[] observedTraceId = new String[1];
        filter.filter(exchange, ex -> {
            observedTraceId[0] = ex.getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.TRACE_ID_HEADER);
            return Mono.empty();
        }).block();

        assertNotNull(observedTraceId[0]);
        assertFalse(observedTraceId[0].isBlank());
    }

    @Test
    void preservesAnExistingClientSuppliedTraceId() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/chat")
                .header(CorrelationIdGlobalFilter.TRACE_ID_HEADER, "client-supplied-trace-id")
                .build());

        String[] observedTraceId = new String[1];
        filter.filter(exchange, ex -> {
            observedTraceId[0] = ex.getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.TRACE_ID_HEADER);
            return Mono.empty();
        }).block();

        assertEquals("client-supplied-trace-id", observedTraceId[0]);
    }
}

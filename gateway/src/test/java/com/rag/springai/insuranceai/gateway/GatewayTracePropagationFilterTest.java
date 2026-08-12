package com.rag.springai.insuranceai.gateway;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the FASE 25 incident this filter fixes: without it, the gateway's outbound
 * proxy call carried no W3C {@code traceparent} header at all, so the backend always started a
 * brand-new, disconnected trace instead of continuing the gateway's - see the filter's own Javadoc
 * (which documents all three real bugs found) and {@code docs/observability/DISTRIBUTED_TRACING.md}
 * section 6.
 *
 * <p>Note this test constructs the filter with a real, locally-built {@link OpenTelemetrySdk}
 * rather than letting it reach for {@code GlobalOpenTelemetry}: using the global instance was
 * itself one of the three production bugs (it silently yields a no-op propagator that injects
 * nothing), so the filter takes the Spring-managed {@code OpenTelemetry} bean by constructor and
 * this test mirrors that.
 */
class GatewayTracePropagationFilterTest {

    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();

    private final GatewayTracePropagationFilter filter = new GatewayTracePropagationFilter(sdk);

    @Test
    void injectsATraceparentHeaderDerivedFromTheCurrentlyActiveSpan() {
        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("inbound-server-span").startSpan();
        String expectedTraceId = span.getSpanContext().getTraceId();

        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/chat").build());
        String[] observedTraceparent = new String[1];

        try (Scope ignored = span.makeCurrent()) {
            filter.filter(exchange, ex -> {
                observedTraceparent[0] = ex.getRequest().getHeaders().getFirst("traceparent");
                return Mono.empty();
            }).block();
        }
        finally {
            span.end();
        }

        assertNotNull(observedTraceparent[0], "no traceparent header was injected at all");
        assertFalse(observedTraceparent[0].isBlank());
        // W3C traceparent format: version-traceId-spanId-flags
        assertTrue(observedTraceparent[0].contains(expectedTraceId),
                "traceparent header must carry the currently active span's trace id: " + observedTraceparent[0]);
    }

    @Test
    void injectsNoTraceparentHeaderWhenNoSpanIsCurrent() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/chat").build());
        String[] observedTraceparent = new String[1];

        filter.filter(exchange, ex -> {
            observedTraceparent[0] = ex.getRequest().getHeaders().getFirst("traceparent");
            return Mono.empty();
        }).block();

        assertNull(observedTraceparent[0]);
    }
}

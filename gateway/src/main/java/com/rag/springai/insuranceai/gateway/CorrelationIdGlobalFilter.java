package com.rag.springai.insuranceai.gateway;

import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * FASE 19: assigns {@code X-Trace-Id} at the true edge of the system if the client didn't supply
 * one, so correlation covers the gateway hop too, not just backend-internal processing - the
 * backend's own {@code TraceIdFilter} (unchanged) reuses whatever value it receives rather than
 * generating a second, disconnected id, so a single trace id covers the client's full journey.
 *
 * <p><b>This is request correlation, not distributed tracing</b>: a single opaque ID threaded
 * through a custom header and SLF4J's MDC, not W3C {@code traceparent}/spans/OpenTelemetry. No
 * OpenTelemetry SDK, exporter, or collector exists anywhere in this codebase (verified: no
 * {@code micrometer-tracing}/{@code opentelemetry-*} dependency in either module's {@code
 * pom.xml}, no tracing config in either {@code application.yaml}) - see {@code
 * docs/observability/OBSERVABILITY.md}'s explicit Production Gap Analysis entry. A prior version
 * of this Javadoc claimed this was "superseded by full W3C traceparent propagation" citing a
 * {@code docs/observability/DISTRIBUTED_TRACING.md} that was never actually written - that claim
 * was false and has been removed.
 */
@Component
public class CorrelationIdGlobalFilter implements WebFilter, Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String existing = request.getHeaders().getFirst(TRACE_ID_HEADER);
        if (existing != null && !existing.isBlank()) {
            return chain.filter(exchange);
        }

        String generated = UUID.randomUUID().toString();
        ServerHttpRequest mutatedRequest = request.mutate().header(TRACE_ID_HEADER, generated).build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        // Runs immediately after TrustedHeaderStrippingFilter (HIGHEST_PRECEDENCE) - a spoofed
        // identity header must never be present even transiently before this filter runs.
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}

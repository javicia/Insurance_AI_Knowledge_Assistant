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
 * Superseded in FASE 22 by full W3C {@code traceparent} propagation (see
 * {@code docs/observability/DISTRIBUTED_TRACING.md}) - this header is kept for backward
 * compatibility with the existing MDC-based backend logging correlation.
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

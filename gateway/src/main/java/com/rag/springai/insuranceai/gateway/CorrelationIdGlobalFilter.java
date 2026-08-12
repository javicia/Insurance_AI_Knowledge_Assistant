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
 * through a custom header ({@value #TRACE_ID_HEADER}) and SLF4J's MDC key {@code correlationId},
 * not W3C {@code traceparent}/spans/OpenTelemetry. FASE 25 added a real, separate OpenTelemetry
 * pipeline (SDK, OTel Collector, Jaeger - see {@code docs/observability/DISTRIBUTED_TRACING.md})
 * with its own {@code trace_id}/{@code span_id} MDC keys - the two identifier systems exist
 * deliberately side by side (see that document section 4 for exactly why they are not merged into
 * one), not one superseding the other. This filter and its {@code X-Trace-Id} header are
 * unaffected by FASE 25 and remain the business-facing correlation id returned to API clients
 * (see the chat response's own {@code traceId} field).
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

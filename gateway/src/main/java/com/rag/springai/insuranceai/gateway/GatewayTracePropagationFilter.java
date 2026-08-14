package com.rag.springai.insuranceai.gateway;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * (Distributed Tracing) incident fix: manually injects the W3C {@code traceparent}/
 * {@code tracestate} headers onto the outbound request before Spring Cloud Gateway's built-in
 * {@code NettyRoutingFilter} proxies it to the backend, so the backend continues the gateway's
 * trace instead of starting a disconnected one.
 *
 * <p><b>Why this filter is needed at all</b>: {@code opentelemetry-spring-boot-starter} (see
 * gateway's {@code pom.xml}) instruments the gateway's <i>inbound</i> WebFlux server request - a
 * real span, confirmed in Jaeger - but {@code NettyRoutingFilter} proxies every request through
 * reactor-netty's raw {@code HttpClient} directly, never through a Spring-managed
 * {@code WebClient}/{@code RestClient} bean, so none of the starter's bundled client
 * instrumentation ever touches that outbound call and no propagation header was ever sent.
 * OpenTelemetry publishes no importable reactor-netty client instrumentation for this project's
 * non-javaagent "library instrumentation" integration style (raw-Netty modules upstream are
 * javaagent-only bytecode transformers), so the propagation is performed explicitly here.
 *
 * <p><b>Three real bugs were fixed to make this work</b>, each found by querying Jaeger's HTTP API
 * for a real end-to-end request rather than by inspection - documented because each failed
 * silently, with a 200 response and no error anywhere:
 * <ol>
 *   <li><b>Executing at assembly time instead of subscription time.</b> Reading the context
 *       directly in the filter body (outside any deferred operator) runs during reactive-chain
 *       assembly, before the inbound server span is active. {@link Mono#deferContextual} defers
 *       the whole body to subscription time, when it is.</li>
 *   <li><b>Reading the context from the wrong place.</b> {@code
 *       ContextPropagationOperator.getOpenTelemetryContextFromContextView} (the obvious candidate,
 *       from {@code opentelemetry-reactor-3.1}) returns an <i>invalid</i>, empty span context here
 *       - Spring Cloud Gateway's filter chain does not carry the OTel context in the Reactor
 *       {@code Context} map that operator reads. {@link Context#current()} <i>is</i> correct once
 *       fix (1) is in place, confirmed by logging the resolved {@code SpanContext} on a real
 *       request ({@code valid=true}, matching the trace id the logging MDC independently
 *       reported).</li>
 *   <li><b>Using the global {@link OpenTelemetry} instance.</b> {@code
 *       GlobalOpenTelemetry.getPropagators()} silently returns a <b>no-op</b> propagator that
 *       injects nothing: the OpenTelemetry Spring Boot starter registers a real
 *       {@link OpenTelemetry} <i>bean</i> but does not necessarily install it as the JVM-global
 *       instance. Symptom: a valid span context, an {@code inject()} call that completed without
 *       error, and no header ever written. Fixed by constructor-injecting the Spring-managed bean
 *       below, which carries the SDK's real, configured
 *       {@link io.opentelemetry.context.propagation.TextMapPropagator}.</li>
 * </ol>
 *
 * <p>See {@code docs/observability/DISTRIBUTED_TRACING.md} section 6 for the end-to-end
 * verification evidence (gateway and backend spans sharing one trace id, with the backend's HTTP
 * span as a {@code CHILD_OF} child of the gateway's).
 */
@Component
public class GatewayTracePropagationFilter implements GlobalFilter, Ordered {

    private final OpenTelemetry openTelemetry;

    public GatewayTracePropagationFilter(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // deferContextual, not a plain body: this must run at subscription time, when the inbound
        // server span is actually current - see bug (1) in this class's Javadoc.
        return Mono.deferContextual(contextView -> {
            ServerHttpRequest.Builder mutatedRequestBuilder = exchange.getRequest().mutate();
            openTelemetry.getPropagators().getTextMapPropagator()
                    .inject(Context.current(), mutatedRequestBuilder,
                            (requestBuilder, headerName, headerValue) -> requestBuilder.header(headerName, headerValue));
            ServerHttpRequest mutatedRequest = mutatedRequestBuilder.build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        });
    }

    @Override
    public int getOrder() {
        // As late as possible - immediately before NettyRoutingFilter (Ordered.LOWEST_PRECEDENCE)
        // actually opens the connection to the backend, so no later filter can strip or overwrite
        // these headers before the proxied request leaves the gateway.
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}

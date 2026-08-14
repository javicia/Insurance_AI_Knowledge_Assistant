package com.rag.springai.insuranceai.gateway;

import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * (brief: "El cliente NO debe poder falsificar X-User, X-Roles, X-Principal,
 * X-Authenticated-User. Si esos headers existen, deben eliminarse antes de llegar al backend").
 *
 * <p>Today nothing downstream trusts these headers at all (the backend independently validates
 * the real {@code Authorization} JWT on every request - see {@code SecurityConfiguration} -
 * rather than trusting any gateway-set identity header), so this filter has no observable effect
 * on a legitimate request. It exists anyway as a structural guarantee: if a future change ever
 * introduced a header-trusting shortcut downstream, a client attempting to forge one of these
 * four headers is still stripped at the true edge, unconditionally, before that shortcut could
 * ever be reached.
 */
@Component
public class TrustedHeaderStrippingFilter implements WebFilter, Ordered {

    static final List<String> FORBIDDEN_CLIENT_HEADERS = List.of("X-User", "X-Roles", "X-Principal",
            "X-Authenticated-User");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest.Builder mutatedRequest = exchange.getRequest().mutate();
        for (String header : FORBIDDEN_CLIENT_HEADERS) {
            mutatedRequest.headers(headers -> headers.remove(header));
        }
        return chain.filter(exchange.mutate().request(mutatedRequest.build()).build());
    }

    @Override
    public int getOrder() {
        // Runs before CorrelationIdGlobalFilter so a spoofed identity header is never present for
        // any later filter or the routed backend call to observe, even transiently.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

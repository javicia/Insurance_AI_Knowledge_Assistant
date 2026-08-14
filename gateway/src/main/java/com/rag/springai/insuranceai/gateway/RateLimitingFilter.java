package com.rag.springai.insuranceai.gateway;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Real per-identity, per-route rate limiting (brief: "limite por identidad autenticada",
 * "limites diferentes para endpoints sensibles", "no implementes un contador global ingenuo").
 * Each {@code (subject, route)} pair gets its own token bucket - a burst-tolerant, steadily-
 * refilling limit, not a fixed window counter that resets unfairly at a boundary.
 *
 * <p><b>Why in-memory, not Redis, for this PoC</b>: a single gateway instance has no state to
 * coordinate - an in-memory {@link ConcurrentHashMap} of buckets is correct and requires no extra
 * infrastructure. This stops being correct the moment a second gateway instance is added (each
 * instance would enforce its own independent limit, silently multiplying the real ceiling a
 * client experiences) - documented here explicitly rather than silently outgrown:
 * {@code bucket4j-redis}'s {@code LettuceBasedProxyManager} is a drop-in replacement for the
 * {@link #buckets} map that coordinates buckets across instances via Redis, with no change to the
 * bucket-construction/consumption logic below. See {@code docs/architecture/API_GATEWAY.md}
 * "Rate limiting" section for the full migration note.
 */
@Component
public class RateLimitingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    /** Requests per minute, per authenticated user, per route family (brief FASE 20 example values). */
    private enum RouteLimit {
        CHAT("/api/chat", 60),
        DOCUMENTS("/api/documents", 10),
        GOVERNANCE("/api/governance", 30),
        AUDIT("/api/audit", 30),
        EVALUATION("/api/evaluation", 5);

        final String pathPrefix;
        final int requestsPerMinute;

        RouteLimit(String pathPrefix, int requestsPerMinute) {
            this.pathPrefix = pathPrefix;
            this.requestsPerMinute = requestsPerMinute;
        }

        static RouteLimit forPath(String path) {
            for (RouteLimit limit : values()) {
                if (path.startsWith(limit.pathPrefix)) {
                    return limit;
                }
            }
            return null;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final GatewaySecurityEventLogger securityEventLogger;

    public RateLimitingFilter(GatewaySecurityEventLogger securityEventLogger) {
        this.securityEventLogger = securityEventLogger;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        RouteLimit routeLimit = RouteLimit.forPath(path);
        if (routeLimit == null) {
            return chain.filter(exchange);
        }

        // switchIfEmpty is deliberately scoped to only this subject-resolution step, not chained
        // after applyLimit - an earlier version chained it after the whole flatMap, which meant
        // "the downstream chain.filter(exchange) completed empty" (the normal case for every
        // successful request, since a routed response has no meaningful completion value) was
        // indistinguishable from "no security context was found", causing applyLimit to run a
        // second time for every single request - silently consuming two tokens instead of one,
        // and crashing outright once a 429 response had already been committed once. Found via
        // RateLimitingFilterTest actually asserting per-request token counts, not by inspection.
        Mono<String> subjectMono = ReactiveSecurityContextHolder.getContext()
                .map(org.springframework.security.core.context.SecurityContext::getAuthentication)
                .map(this::subjectOf)
                // No authenticated principal (should not normally happen - every /api/** route
                // requires authentication, see GatewaySecurityConfiguration) - fall back to the
                // client's remote address rather than skip rate limiting entirely.
                .switchIfEmpty(Mono.just(remoteAddressOf(exchange)));

        return subjectMono.flatMap(subject -> applyLimit(exchange, chain, routeLimit, subject));
    }

    private Mono<Void> applyLimit(ServerWebExchange exchange, GatewayFilterChain chain, RouteLimit routeLimit,
            String subject) {
        String key = subject + ":" + routeLimit.name();
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(routeLimit.requestsPerMinute));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().add("X-RateLimit-Limit", String.valueOf(routeLimit.requestsPerMinute));

        if (probe.isConsumed()) {
            response.getHeaders().add("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            return chain.filter(exchange);
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000);
        response.getHeaders().add("X-RateLimit-Remaining", "0");
        response.getHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);

        String traceId = exchange.getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.TRACE_ID_HEADER);
        // Best-effort: a broken event sink must never turn a legitimate 429 into an unhandled
        // exception - see the backend's identical SecurityErrorHandler reasoning.
        try {
            securityEventLogger.log(GatewaySecurityEvent.now(GatewaySecurityEventType.RATE_LIMIT_EXCEEDED,
                    traceId != null ? traceId : "unknown", subject, exchange.getRequest().getMethod().name(),
                    exchange.getRequest().getPath().value(), HttpStatus.TOO_MANY_REQUESTS.value(),
                    routeLimit.name()));
        }
        catch (RuntimeException e) {
            log.warn("Failed to emit RATE_LIMIT_EXCEEDED security event - continuing, since security event "
                    + "logging must never break the request it describes", e);
        }

        return response.setComplete();
    }

    private Bucket newBucket(int requestsPerMinute) {
        Bandwidth limit = Bandwidth.classic(requestsPerMinute,
                Refill.greedy(requestsPerMinute, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    private String subjectOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return "user:" + jwtAuth.getToken().getSubject();
        }
        return "principal:" + authentication.getName();
    }

    private String remoteAddressOf(ServerWebExchange exchange) {
        var remoteAddress = exchange.getRequest().getRemoteAddress();
        return "anonymous:" + (remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown");
    }

    @Override
    public int getOrder() {
        // After routing decisions but before the request is proxied to the backend - see
        // Spring Cloud Gateway's NettyRoutingFilter order (LOWEST_PRECEDENCE) for why this value
        // (comfortably before it) is early enough to reject over-limit requests without ever
        // opening a connection to the backend.
        return -1;
    }
}

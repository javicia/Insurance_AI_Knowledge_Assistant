package com.rag.springai.insuranceai.gateway;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitingFilterTest {

    private final RateLimitingFilter filter = new RateLimitingFilter();

    @Test
    void allowsRequestsWithinTheLimitThenReturns429WithRetryAfter() {
        // AUDIT is configured at 30 requests/minute - well within one burst but small enough to
        // exhaust deterministically in a test.
        String userA = "user-a";
        AtomicInteger passedThrough = new AtomicInteger();

        for (int i = 0; i < 30; i++) {
            ServerWebExchange exchange = authenticatedRequest("/api/audit/recent", userA);
            filter.filter(exchange, ex -> {
                passedThrough.incrementAndGet();
                return Mono.empty();
            }).contextWrite(withAuthentication(userA)).block();
            assertTrue(exchange.getResponse().getStatusCode() == null, "request " + i + " should pass through untouched");
        }
        assertEquals(30, passedThrough.get());

        // The 31st request in the same minute for the same user/route must be rejected.
        ServerWebExchange overLimitExchange = authenticatedRequest("/api/audit/recent", userA);
        filter.filter(overLimitExchange, ex -> {
            passedThrough.incrementAndGet();
            return Mono.empty();
        }).contextWrite(withAuthentication(userA)).block();

        assertEquals(429, overLimitExchange.getResponse().getStatusCode().value());
        assertEquals(30, passedThrough.get(), "the 31st request must never reach the downstream chain");
        assertNotNull(overLimitExchange.getResponse().getHeaders().getFirst("Retry-After"));
        assertEquals("0", overLimitExchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
    }

    @Test
    void twoDifferentUsersGetIndependentBuckets() {
        // EVALUATION is configured at 5 requests/minute - exhaust it for user-a only.
        for (int i = 0; i < 5; i++) {
            ServerWebExchange exchange = authenticatedRequest("/api/evaluation/runs/recent", "user-a");
            filter.filter(exchange, ex -> Mono.empty()).contextWrite(withAuthentication("user-a")).block();
        }
        ServerWebExchange exhaustedForA = authenticatedRequest("/api/evaluation/runs/recent", "user-a");
        filter.filter(exhaustedForA, ex -> Mono.empty()).contextWrite(withAuthentication("user-a")).block();
        assertEquals(429, exhaustedForA.getResponse().getStatusCode().value(), "user-a's bucket must be exhausted");

        ServerWebExchange stillOkForB = authenticatedRequest("/api/evaluation/runs/recent", "user-b");
        AtomicInteger reachedChain = new AtomicInteger();
        filter.filter(stillOkForB, ex -> {
            reachedChain.incrementAndGet();
            return Mono.empty();
        }).contextWrite(withAuthentication("user-b")).block();

        assertEquals(1, reachedChain.get(), "a different user must have their own, unaffected bucket");
    }

    @Test
    void twoDifferentRoutesForTheSameUserHaveIndependentPolicies() {
        // Exhaust EVALUATION (5/min) for user-a.
        for (int i = 0; i < 6; i++) {
            ServerWebExchange exchange = authenticatedRequest("/api/evaluation/runs/recent", "user-a");
            filter.filter(exchange, ex -> Mono.empty()).contextWrite(withAuthentication("user-a")).block();
        }

        // AUDIT (30/min) for the same user must be entirely unaffected.
        ServerWebExchange auditExchange = authenticatedRequest("/api/audit/recent", "user-a");
        AtomicInteger reachedChain = new AtomicInteger();
        filter.filter(auditExchange, ex -> {
            reachedChain.incrementAndGet();
            return Mono.empty();
        }).contextWrite(withAuthentication("user-a")).block();

        assertEquals(1, reachedChain.get(), "a different route family must have its own independent limit");
    }

    @Test
    void routesOutsideTheFiveKnownFamiliesAreNotRateLimitedByThisFilter() {
        ServerWebExchange exchange = authenticatedRequest("/actuator/health", "user-a");
        AtomicInteger reachedChain = new AtomicInteger();

        filter.filter(exchange, ex -> {
            reachedChain.incrementAndGet();
            return Mono.empty();
        }).contextWrite(withAuthentication("user-a")).block();

        assertEquals(1, reachedChain.get());
        assertTrue(exchange.getResponse().getHeaders().get("X-RateLimit-Limit") == null,
                "an unrelated route must not carry rate-limit headers at all");
    }

    private ServerWebExchange authenticatedRequest(String path, String user) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private Context withAuthentication(String user) {
        var authentication = new TestingAuthenticationToken(user, null, "ROLE_TEST");
        authentication.setAuthenticated(true);
        return ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl(authentication)));
    }
}

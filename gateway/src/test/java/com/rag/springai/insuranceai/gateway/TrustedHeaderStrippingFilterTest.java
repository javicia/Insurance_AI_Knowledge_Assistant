package com.rag.springai.insuranceai.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedHeaderStrippingFilterTest {

    private final TrustedHeaderStrippingFilter filter = new TrustedHeaderStrippingFilter();

    @Test
    void stripsEveryForbiddenClientSuppliedIdentityHeader() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/chat")
                .header("X-User", "forged-user")
                .header("X-Roles", "AI_GOVERNANCE_ADMIN")
                .header("X-Principal", "forged-principal")
                .header("X-Authenticated-User", "forged-authenticated-user")
                .header("Content-Type", "application/json")
                .build());

        boolean[] observed = { false };
        filter.filter(exchange, ex -> {
            observed[0] = true;
            for (String forbidden : TrustedHeaderStrippingFilter.FORBIDDEN_CLIENT_HEADERS) {
                assertFalse(ex.getRequest().getHeaders().getFirst(forbidden) != null,
                        forbidden + " must be stripped before reaching any downstream filter");
            }
            assertTrue(ex.getRequest().getHeaders().getFirst("Content-Type") != null,
                    "legitimate headers must be preserved");
            return Mono.empty();
        }).block();

        assertTrue(observed[0], "the filter chain must still be invoked");
    }
}

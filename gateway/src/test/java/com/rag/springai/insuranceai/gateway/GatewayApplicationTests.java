package com.rag.springai.insuranceai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

/** Proves the gateway's real application.yaml (routes, CORS) loads into a working context. */
@SpringBootTest
class GatewayApplicationTests {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void contextLoads() {
    }

    /**
     * A real bug slipped past this test suite for a while: the routes block in application.yaml
     * was nested under a custom {@code insurance-ai:} key instead of {@code spring.cloud.gateway},
     * so Spring Cloud Gateway silently registered zero routes and every request 404'd - caught only
     * by manually curling the live gateway, not by any automated test, since {@link #contextLoads()}
     * only proves the context starts, not that the property tree actually bound into routes. This
     * test asserts the route table directly so a repeat of that specific mistake fails the build.
     */
    @Test
    void allFiveExplicitRoutesAreRegistered() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        Set<String> ids = routes.stream().map(Route::getId).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder("chat", "documents", "governance", "audit", "evaluation");
    }
}

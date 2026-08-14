package com.rag.springai.insuranceai.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A genuinely independent Spring Boot application - its own Maven module, its own
 * artifact, its own container image. Knows only routes and cross-cutting technical policy
 * (routing, CORS, correlation id, rate limiting); never imports backend domain/application code
 * (there is no compile-time dependency between this module and {@code backend/} at all - see
 * {@code docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md}).
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

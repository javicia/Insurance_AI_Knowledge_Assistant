package com.rag.springai.insuranceai.adapters.inbound.rest;

import com.rag.springai.insuranceai.DatabaseCleanupExtension;
import com.rag.springai.insuranceai.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 12 (API documentation) acceptance check: {@code /v3/api-docs} genuinely reflects the real
 * controllers - not just that springdoc-openapi compiles into the jar. See
 * {@code docs/adr/ADR-011-API-DOCUMENTATION.md}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(DatabaseCleanupExtension.class)
class OpenApiDocsIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    void openApiDocumentListsEveryControllersBasePath() {
        ResponseEntity<String> response = restTemplate.getForEntity("http://localhost:" + port + "/v3/api-docs",
                String.class);

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertTrue(body.contains("\"/api/chat\""), "chat endpoint must be documented");
        assertTrue(body.contains("\"/api/documents\""), "document management endpoints must be documented");
        assertTrue(body.contains("/api/governance/ai-systems"), "governance endpoints must be documented");
        assertTrue(body.contains("/api/audit/traces/{traceId}"), "audit endpoints must be documented");
        assertTrue(body.contains("/api/evaluation/runs"), "evaluation endpoints must be documented");
    }
}

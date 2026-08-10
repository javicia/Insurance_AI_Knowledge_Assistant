package com.rag.springai.insuranceai.pipeline;

import com.rag.springai.insuranceai.DatabaseCleanupExtension;
import com.rag.springai.insuranceai.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 15 (Angular SPA integration): verifies {@code SpaWebConfiguration}'s resource-resolver
 * fallback against the real Spring MVC handler mapping precedence - not just the resolver in
 * isolation - so a regression that accidentally lets {@code /api/**} fall through to {@code
 * index.html} would genuinely fail this test.
 *
 * <p>Uses a minimal stub {@code index.html}/{@code main.js} under {@code
 * src/test/resources/static/} (classpath-precedent over {@code src/main/resources/static/} during
 * {@code mvnw test}) rather than a real Angular build - the actual packaged Angular application is
 * only present in the Docker-built image and is validated separately by the manual/scripted E2E
 * flow documented in {@code docs/demo/DEMO_GUIDE.md} and {@code FINAL_FRONTEND_AUDIT.md}. This is
 * a deliberate, documented test boundary, not a shortcut presented as full E2E coverage.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(DatabaseCleanupExtension.class)
class SpaWebConfigurationIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void rootServesTheSpaIndexPage() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/"), String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("insurance-ai-spa-fallback-marker"));
    }

    @Test
    void everyAngularRouteFallsBackToTheSpaIndexPageForDirectNavigationOrRefresh() {
        for (String route : new String[] { "/assistant", "/documents", "/governance", "/audit", "/evaluation" }) {
            ResponseEntity<String> response = restTemplate.getForEntity(url(route), String.class);

            assertEquals(200, response.getStatusCode().value(), route + " must resolve to the SPA index page");
            assertTrue(response.getBody().contains("insurance-ai-spa-fallback-marker"),
                    route + " must serve index.html, not a 404, so Angular's router can take over");
        }
    }

    @Test
    void aRealStaticAssetIsServedAsItselfNotAsTheIndexPage() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/main.js"), String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("insurance-ai-spa-fallback-marker-asset"),
                "a real static asset must be served verbatim");
        assertTrue(response.getHeaders().getContentType() != null
                && response.getHeaders().getContentType().isCompatibleWith(MediaType.valueOf("text/javascript")),
                "a .js asset must be served with a JavaScript content type, not text/html");
    }

    @Test
    void anUnmappedApiPathIsARealNotFoundNeverTheSpaFallback() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/this-endpoint-does-not-exist"),
                String.class);

        assertEquals(404, response.getStatusCode().value(),
                "an unknown /api/** path must 404, never silently succeed with index.html");
        assertTrue(response.getBody() == null || !response.getBody().contains("insurance-ai-spa-fallback-marker"),
                "the SPA fallback must never substitute for a missing API endpoint");
    }

    @Test
    void aRealApiEndpointStillResolvesToItsController() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/audit/recent"), String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() == null || !response.getBody().contains("insurance-ai-spa-fallback-marker"),
                "a real, working API endpoint must never be shadowed by the SPA fallback");
    }

    @Test
    void aRealApiPathCalledWithTheWrongHttpMethodIsA405NeverTheSpaFallbackOrA500() {
        // /api/evaluation/runs only supports POST (see EvaluationController) - found via manual
        // E2E verification of every real API route against the Docker-built image.
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/evaluation/runs"), String.class);

        assertEquals(405, response.getStatusCode().value());
        assertTrue(response.getBody() == null || !response.getBody().contains("insurance-ai-spa-fallback-marker"),
                "a wrong-method /api/** request must never be shadowed by the SPA fallback");
    }
}

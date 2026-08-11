package com.rag.springai.insuranceai.pipeline;

import com.rag.springai.insuranceai.DatabaseCleanupExtension;
import com.rag.springai.insuranceai.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FASE 16 (frontend/backend separation): the backend is now an API-only deployable - it no longer
 * serves the Angular SPA or falls back to {@code index.html} ({@code SpaWebConfiguration} was
 * removed; static frontend serving now lives entirely in {@code frontend/}'s own nginx image, see
 * {@code docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md}). What remains true, and is verified
 * here against the real Spring MVC handler mapping precedence rather than in isolation: an
 * unmapped {@code /api/**} path is a real {@code 404}, a real {@code /api/**} path called with the
 * wrong HTTP method is a real {@code 405}, and a real endpoint still resolves correctly.
 *
 * <p>FASE 17 (IAM): every case here now runs authenticated (via spring-security-test's
 * {@code jwt()} request post-processor, which injects a pre-built {@code Authentication} directly
 * without needing a running Keycloak) with a broad enough authority set that these three cases
 * keep testing routing/MVC behavior specifically, not authorization - authorization itself has its
 * own dedicated matrix in {@link SecurityAuthorizationIntegrationTest}. This class replaces the
 * former {@code SpaWebConfigurationIntegrationTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(DatabaseCleanupExtension.class)
class ApiRoutingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor allAuthorities() {
        return jwt().authorities(new SimpleGrantedAuthority("CHAT_READ"), new SimpleGrantedAuthority("DOCUMENT_UPLOAD"),
                new SimpleGrantedAuthority("GOVERNANCE_READ"), new SimpleGrantedAuthority("GOVERNANCE_WRITE"),
                new SimpleGrantedAuthority("AUDIT_READ"), new SimpleGrantedAuthority("EVALUATION_READ"),
                new SimpleGrantedAuthority("EVALUATION_EXECUTE"));
    }

    @Test
    void anUnmappedApiPathIsARealNotFound() throws Exception {
        mockMvc.perform(get("/api/this-endpoint-does-not-exist").with(allAuthorities()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRealApiEndpointResolvesToItsController() throws Exception {
        mockMvc.perform(get("/api/audit/recent").with(allAuthorities()))
                .andExpect(status().isOk());
    }

    @Test
    void aRealApiPathCalledWithTheWrongHttpMethodIsA405() throws Exception {
        // /api/evaluation/runs only supports POST (see EvaluationController).
        mockMvc.perform(get("/api/evaluation/runs").with(allAuthorities()))
                .andExpect(status().isMethodNotAllowed());
    }
}

package com.rag.springai.insuranceai.pipeline;

import com.rag.springai.insuranceai.DatabaseCleanupExtension;
import com.rag.springai.insuranceai.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FASE 17 (IAM) - the endpoint/authority enforcement matrix (brief section 5):
 *
 * <pre>
 * Endpoint                        | Anonymous | AI_USER | GOVERNANCE_ADMIN | AI_AUDITOR | AI_EVALUATION_ADMIN
 * POST /api/chat                  |    401    |   OK    |       403        |    403     |        403
 * POST /api/documents             |    401    |   OK    |       403        |    403     |        403
 * GET  /api/governance/**         |    401    |   OK    |        OK        |    403     |        403
 * POST /api/governance/**         |    401    |   403   |        OK        |    403     |        403
 * GET  /api/audit/**              |    401    |   403   |       403        |     OK     |        403
 * GET  /api/evaluation/**         |    401    |   403   |       403        |    403     |         OK
 * POST /api/evaluation/runs       |    401    |   403   |       403        |    403     |         OK
 * </pre>
 *
 * Each "OK" case only needs to prove the request reached the controller (not 401/403) - the
 * controller's own business-logic correctness is already covered by the dedicated pipeline
 * integration tests ({@code RagPipelineIntegrationTest}, {@code GovernanceIntegrationTest}, etc.),
 * so request bodies here are minimal-but-valid rather than realistic. This class uses
 * spring-security-test's {@code jwt()} request post-processor (a pre-built {@code Authentication}
 * injected directly, no real Keycloak needed) to isolate authorization-rule coverage from
 * signature/issuer validation, which {@code KeycloakJwtValidationTest} covers separately against
 * a real identity provider.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(DatabaseCleanupExtension.class)
class SecurityAuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private RequestPostProcessor as(String... authorities) {
        SimpleGrantedAuthority[] granted = new SimpleGrantedAuthority[authorities.length];
        for (int i = 0; i < authorities.length; i++) {
            granted[i] = new SimpleGrantedAuthority(authorities[i]);
        }
        return jwt().authorities(granted);
    }

    // --- POST /api/chat -> CHAT_READ ---

    @Test
    void chatAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(chatBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatWithChatReadAuthorityIsAllowedPastAuthorization() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(chatBody())
                        .with(as("CHAT_READ")))
                .andExpect(status().isOk());
    }

    @Test
    void chatWithOnlyAuditReadIsForbidden() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(chatBody())
                        .with(as("AUDIT_READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void chatWithOnlyGovernanceAuthoritiesIsForbidden() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(chatBody())
                        .with(as("GOVERNANCE_READ", "GOVERNANCE_WRITE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void chatWithOnlyEvaluationAuthoritiesIsForbidden() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(chatBody())
                        .with(as("EVALUATION_READ", "EVALUATION_EXECUTE")))
                .andExpect(status().isForbidden());
    }

    // --- POST /api/documents -> DOCUMENT_UPLOAD ---

    @Test
    void documentUploadAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(documentUploadRequest()).andExpect(status().isUnauthorized());
    }

    @Test
    void documentUploadWithDocumentUploadAuthorityIsAllowedPastAuthorization() throws Exception {
        mockMvc.perform(documentUploadRequest().with(as("DOCUMENT_UPLOAD")))
                .andExpect(status().isCreated());
    }

    @Test
    void documentUploadWithOnlyAuditReadIsForbidden() throws Exception {
        mockMvc.perform(documentUploadRequest().with(as("AUDIT_READ"))).andExpect(status().isForbidden());
    }

    // --- GET /api/governance/** -> GOVERNANCE_READ ---

    @Test
    void governanceReadAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/governance/ai-systems")).andExpect(status().isUnauthorized());
    }

    @Test
    void governanceReadWithAiUserAuthorityIsAllowed() throws Exception {
        mockMvc.perform(get("/api/governance/ai-systems").with(as("GOVERNANCE_READ")))
                .andExpect(status().isOk());
    }

    @Test
    void governanceReadWithGovernanceAdminAuthorityIsAllowed() throws Exception {
        mockMvc.perform(get("/api/governance/ai-systems").with(as("GOVERNANCE_READ", "GOVERNANCE_WRITE")))
                .andExpect(status().isOk());
    }

    @Test
    void governanceReadWithOnlyAuditReadIsForbidden() throws Exception {
        mockMvc.perform(get("/api/governance/ai-systems").with(as("AUDIT_READ")))
                .andExpect(status().isForbidden());
    }

    // --- POST /api/governance/** -> GOVERNANCE_WRITE (AI_USER's GOVERNANCE_READ is not enough) ---

    @Test
    void governanceWriteAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/governance/ai-systems").contentType(MediaType.APPLICATION_JSON)
                        .content(registerAiSystemBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void governanceWriteWithOnlyGovernanceReadIsForbidden() throws Exception {
        mockMvc.perform(post("/api/governance/ai-systems").contentType(MediaType.APPLICATION_JSON)
                        .content(registerAiSystemBody()).with(as("GOVERNANCE_READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void governanceWriteWithGovernanceWriteAuthorityIsAllowedPastAuthorization() throws Exception {
        mockMvc.perform(post("/api/governance/ai-systems").contentType(MediaType.APPLICATION_JSON)
                        .content(registerAiSystemBody()).with(as("GOVERNANCE_READ", "GOVERNANCE_WRITE")))
                .andExpect(status().isCreated());
    }

    // --- GET /api/audit/** -> AUDIT_READ ---

    @Test
    void auditReadAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/audit/recent")).andExpect(status().isUnauthorized());
    }

    @Test
    void auditReadWithAuditorAuthorityIsAllowed() throws Exception {
        mockMvc.perform(get("/api/audit/recent").with(as("AUDIT_READ"))).andExpect(status().isOk());
    }

    @Test
    void auditReadWithAiUserAuthoritiesIsForbidden() throws Exception {
        mockMvc.perform(get("/api/audit/recent").with(as("CHAT_READ", "DOCUMENT_UPLOAD", "GOVERNANCE_READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditReadWithGovernanceAdminAuthoritiesIsForbidden() throws Exception {
        mockMvc.perform(get("/api/audit/recent").with(as("GOVERNANCE_READ", "GOVERNANCE_WRITE")))
                .andExpect(status().isForbidden());
    }

    // --- GET/POST /api/evaluation/** -> EVALUATION_READ / EVALUATION_EXECUTE ---

    @Test
    void evaluationReadAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/evaluation/runs/recent")).andExpect(status().isUnauthorized());
    }

    @Test
    void evaluationReadWithEvaluationAuthorityIsAllowed() throws Exception {
        mockMvc.perform(get("/api/evaluation/runs/recent").with(as("EVALUATION_READ")))
                .andExpect(status().isOk());
    }

    @Test
    void evaluationReadWithAuditorAuthorityIsForbidden() throws Exception {
        mockMvc.perform(get("/api/evaluation/runs/recent").with(as("AUDIT_READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void evaluationExecuteAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/evaluation/runs")).andExpect(status().isUnauthorized());
    }

    @Test
    void evaluationExecuteWithOnlyEvaluationReadIsForbidden() throws Exception {
        mockMvc.perform(post("/api/evaluation/runs").with(as("EVALUATION_READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void evaluationExecuteWithEvaluationExecuteAuthorityIsAllowedPastAuthorization() throws Exception {
        mockMvc.perform(post("/api/evaluation/runs").with(as("EVALUATION_READ", "EVALUATION_EXECUTE")))
                .andExpect(status().isCreated());
    }

    // --- Actuator: health is public, everything else needs GOVERNANCE_READ ---

    @Test
    void actuatorHealthIsPublicForDockerHealthchecks() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void actuatorInfoRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }

    private String chatBody() {
        return "{\"question\":\"Is water damage covered?\"}";
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder documentUploadRequest() {
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", MediaType.APPLICATION_PDF_VALUE,
                "dummy content".getBytes());
        return multipart("/api/documents").file(file)
                .param("name", "Test Policy")
                .param("type", "POLICY")
                .param("classification", "PUBLIC");
    }

    /**
     * FASE 23 incident follow-up (2026-08-11): a real full-suite run failed with a Postgres
     * {@code duplicate key value violates unique constraint "ai_systems_name_key"} - root-caused
     * to {@code governanceWriteWithGovernanceWriteAuthorityIsAllowedPastAuthorization} (the only
     * one of the three tests using this body that actually reaches the database - the other two
     * are rejected by authorization first) inserting a real row under a fixed name, with no
     * cleanup, combined with this project's {@code TestcontainersConfiguration} intentionally
     * reusing the same Postgres container ({@code withReuse(true)}) across separate {@code
     * ./mvnw} invocations for local development speed (see {@code docs/testing/TESTCONTAINERS.md}).
     * A fixed name is therefore only ever safe to insert once per container lifetime - a random
     * suffix makes every real insert unique instead, which is the correct fix (this test's whole
     * point is proving an authorized POST reaches {@code 201}, not asserting on a specific name).
     */
    private String registerAiSystemBody() {
        return """
                {
                  "name": "Test AI System %s",
                  "purpose": "Test purpose",
                  "owner": "Test Owner",
                  "intendedUse": "Test intended use",
                  "prohibitedUse": "Test prohibited use",
                  "riskClassification": "LIMITED",
                  "humanOversightRequired": true,
                  "humanOversightWhenRequired": "Always",
                  "humanOversightEscalationCondition": "Any customer-specific decision",
                  "humanOversightDecisionResponsibility": "The employee's manager"
                }
                """.formatted(java.util.UUID.randomUUID());
    }
}

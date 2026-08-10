package com.rag.springai.insuranceai.infrastructure.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FASE 12 (API documentation): metadata shown at {@code /swagger-ui.html} and the top of
 * {@code /v3/api-docs} - springdoc generates the actual paths/schemas from the
 * {@code @RestController}/DTO classes themselves (see {@code docs/adr/ADR-011-API-DOCUMENTATION.md}),
 * this bean only supplies the human-readable title/description/PoC disclaimer.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI insuranceKnowledgeAssistantOpenApi() {
        return new OpenAPI().info(new Info().title("Insurance Knowledge Assistant API")
                .description("""
                        RAG-based internal insurance knowledge assistant. THIS IS AN ENTERPRISE-GRADE \
                        ARCHITECTURAL PoC, NOT A PRODUCTION CERTIFICATION - see the repository README and \
                        docs/governance/AI_ACT.md for what that does and does not mean.

                        The system informs; a human always decides (no claim/pricing/eligibility/underwriting \
                        decision is ever made here - see docs/governance/HUMAN_OVERSIGHT.md). Every grounded \
                        answer is traceable end to end via its traceId (see docs/audit/AI_AUDIT.md).""")
                .version("v0")
                .contact(new Contact().name("AI Platform Team")));
    }
}

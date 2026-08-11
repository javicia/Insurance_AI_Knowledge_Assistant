package com.rag.springai.insuranceai.application.governance;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;

/**
 * Fixed identity of the one AI system this PoC registers (brief FASE 9 section 49's governance
 * demo) - seeded by {@code V5__ai_governance.sql}. A single constant shared by every collaborator
 * that needs to reference it ({@code AskInsuranceKnowledgeUseCase}, {@code ModelRegistrySeeder})
 * rather than duplicating the literal UUID.
 */
public final class WellKnownAiSystems {

    public static final AiSystemId INSURANCE_KNOWLEDGE_ASSISTANT =
            AiSystemId.of("f7c001e3-92b2-5f65-9fc4-33cdb3cc7774");

    public static final String INSURANCE_RAG_SYSTEM_PROMPT_KEY = "insurance-rag-system-prompt";

    private WellKnownAiSystems() {
    }
}

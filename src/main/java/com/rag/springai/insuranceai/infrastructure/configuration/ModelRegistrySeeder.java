package com.rag.springai.insuranceai.infrastructure.configuration;

import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import com.rag.springai.insuranceai.application.governance.ModelRegistryService;
import com.rag.springai.insuranceai.application.governance.WellKnownAiSystems;
import com.rag.springai.insuranceai.domain.model.ModelStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * Registers the actually-configured LLM provider in the Model Registry at startup (brief FASE 9
 * section 10). Not done in {@code V5__ai_governance.sql} because which provider is active
 * depends on {@code insurance-ai.ai.provider} - a migration-time value would be wrong for
 * whichever provider isn't currently selected.
 */
@Component
public class ModelRegistrySeeder {

    private final ModelRegistryService modelRegistryService;
    private final InsuranceAiProperties properties;

    public ModelRegistrySeeder(ModelRegistryService modelRegistryService, InsuranceAiProperties properties) {
        this.modelRegistryService = Objects.requireNonNull(modelRegistryService,
                "modelRegistryService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerActiveProviderIfMissing() {
        String provider = properties.ai().provider().name().toLowerCase(Locale.ROOT);
        boolean alreadyRegistered = modelRegistryService.listByAiSystem(WellKnownAiSystems.INSURANCE_KNOWLEDGE_ASSISTANT).stream()
                .anyMatch(model -> model.provider().equals(provider) && model.status() == ModelStatus.ACTIVE);
        if (alreadyRegistered) {
            return;
        }

        String modelIdentifier;
        String capabilities;
        String intendedPurpose;
        switch (properties.ai().provider()) {
            case OPENAI -> {
                modelIdentifier = "text-embedding-3-small (embeddings) + OpenAI chat model";
                capabilities = "Chat completion + embeddings";
                intendedPurpose = "Production LLM provider";
            }
            case ANTHROPIC -> {
                modelIdentifier = "Anthropic chat model (embeddings via OpenAI - see docs/rag/EMBEDDINGS.md)";
                capabilities = "Chat completion";
                intendedPurpose = "Production LLM provider";
            }
            default -> {
                modelIdentifier = "deterministic offline echo/hashing adapters";
                capabilities = "Test/offline pipeline validation";
                intendedPurpose = "Never used in production - see FakeLlmAdapter/FakeEmbeddingModelAdapter";
            }
        }

        modelRegistryService.register(WellKnownAiSystems.INSURANCE_KNOWLEDGE_ASSISTANT, provider, modelIdentifier, null, capabilities,
                intendedPurpose);
    }
}

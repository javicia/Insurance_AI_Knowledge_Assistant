package com.rag.springai.insuranceai.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * Typed binding for the {@code insurance-ai.*} configuration namespace (brief section 48).
 * Only the configuration surface needed by FASE 1 (structure, not behaviour) is bound here;
 * fields are consumed by RAG, security and governance components as those are implemented in
 * later phases.
 */
@ConfigurationProperties(prefix = "insurance-ai")
public record InsuranceAiProperties(Rag rag, Security security, Governance governance, Ai ai) {

    public InsuranceAiProperties {
        Objects.requireNonNull(rag, "insurance-ai.rag must be configured");
        Objects.requireNonNull(security, "insurance-ai.security must be configured");
        Objects.requireNonNull(governance, "insurance-ai.governance must be configured");
        Objects.requireNonNull(ai, "insurance-ai.ai must be configured");
    }

    public record Rag(int topK, double similarityThreshold) {
        public Rag {
            if (topK <= 0) {
                throw new IllegalArgumentException("insurance-ai.rag.top-k must be positive");
            }
            if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
                throw new IllegalArgumentException(
                        "insurance-ai.rag.similarity-threshold must be between 0.0 and 1.0");
            }
        }
    }

    public record Security(PromptInjection promptInjection, Pii pii) {

        public record PromptInjection(boolean enabled) {
        }

        public record Pii(boolean enabled) {
        }
    }

    public record Governance(Audit audit) {

        public record Audit(boolean enabled) {
        }
    }

    public record Ai(SupportedAiProvider provider) {
        public Ai {
            Objects.requireNonNull(provider, "insurance-ai.ai.provider must be configured");
        }
    }

    /**
     * Selects which {@code LlmProvider} port implementation is wired at startup (brief
     * section 16). Kept separate from the {@code LlmProvider} port itself so that
     * configuration parsing never depends on the port/adapter code it selects between.
     *
     * <p>{@code FAKE} selects a deterministic, offline adapter
     * ({@code adapters.outbound.llm.FakeLlmAdapter}) used only by the automated test suite and
     * by manual end-to-end validation when no real provider credentials are available (brief
     * section 17/18) - never a production option, and never claimed as "provider validated".
     */
    public enum SupportedAiProvider {
        OPENAI,
        ANTHROPIC,
        FAKE
    }
}

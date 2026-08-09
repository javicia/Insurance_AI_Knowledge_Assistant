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

    /**
     * FASE 6 (Advanced RAG) configuration surface for {@code AskInsuranceKnowledgeUseCase}'s
     * hybrid retrieval pipeline. Replaces the FASE 5 flat {@code top-k}/{@code
     * similarity-threshold} pair (previously injected field-by-field via {@code @Value}) with
     * the typed {@code @ConfigurationProperties} mechanism this record already provided but
     * that {@code AskInsuranceKnowledgeUseCase} did not yet consume - see brief section 43.
     * Nested records keep each retrieval stage's knobs grouped and named after the stage they
     * configure, matching the pipeline in {@code docs/rag/HYBRID_SEARCH.md}.
     */
    public record Rag(Semantic semantic, Lexical lexical, Hybrid hybrid, Reranking reranking,
            QueryExpansion queryExpansion, Context context) {

        public Rag {
            Objects.requireNonNull(semantic, "insurance-ai.rag.semantic must be configured");
            Objects.requireNonNull(lexical, "insurance-ai.rag.lexical must be configured");
            Objects.requireNonNull(hybrid, "insurance-ai.rag.hybrid must be configured");
            Objects.requireNonNull(reranking, "insurance-ai.rag.reranking must be configured");
            Objects.requireNonNull(queryExpansion, "insurance-ai.rag.query-expansion must be configured");
            Objects.requireNonNull(context, "insurance-ai.rag.context must be configured");
        }

        /** Vector similarity search (FASE 5's original, unchanged, production default 0.75). */
        public record Semantic(int topK, double similarityThreshold) {
            public Semantic {
                if (topK <= 0) {
                    throw new IllegalArgumentException("insurance-ai.rag.semantic.top-k must be positive");
                }
                if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
                    throw new IllegalArgumentException(
                            "insurance-ai.rag.semantic.similarity-threshold must be between 0.0 and 1.0");
                }
            }
        }

        /** PostgreSQL full-text search (BM25-style lexical retrieval). */
        public record Lexical(int topK) {
            public Lexical {
                if (topK <= 0) {
                    throw new IllegalArgumentException("insurance-ai.rag.lexical.top-k must be positive");
                }
            }
        }

        /**
         * Score fusion (Reciprocal Rank Fusion) and the candidate pool carried into reranking.
         * {@code rrfK} is RRF's own smoothing constant (not a top-K count) - see
         * {@code docs/rag/HYBRID_SEARCH.md} for why 60 (the original RRF paper's value) is used.
         */
        public record Hybrid(int candidatePoolSize, int finalTopK, double rrfK) {
            public Hybrid {
                if (candidatePoolSize <= 0) {
                    throw new IllegalArgumentException(
                            "insurance-ai.rag.hybrid.candidate-pool-size must be positive");
                }
                if (finalTopK <= 0) {
                    throw new IllegalArgumentException("insurance-ai.rag.hybrid.final-top-k must be positive");
                }
                if (finalTopK > candidatePoolSize) {
                    throw new IllegalArgumentException(
                            "insurance-ai.rag.hybrid.final-top-k must not exceed candidate-pool-size");
                }
                if (rrfK <= 0.0) {
                    throw new IllegalArgumentException("insurance-ai.rag.hybrid.rrf-k must be positive");
                }
            }
        }

        public record Reranking(boolean enabled) {
        }

        public record QueryExpansion(boolean enabled, int maxExpandedTerms) {
            public QueryExpansion {
                if (maxExpandedTerms < 0) {
                    throw new IllegalArgumentException(
                            "insurance-ai.rag.query-expansion.max-expanded-terms must not be negative");
                }
            }
        }

        /** PoC approximation of a context window budget - character count, not real token counting. */
        public record Context(int maxCharacters) {
            public Context {
                if (maxCharacters <= 0) {
                    throw new IllegalArgumentException("insurance-ai.rag.context.max-characters must be positive");
                }
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

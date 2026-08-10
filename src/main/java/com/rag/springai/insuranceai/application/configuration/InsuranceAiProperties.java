package com.rag.springai.insuranceai.application.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * Typed binding for the {@code insurance-ai.*} configuration namespace (brief section 48).
 *
 * <p>Lives in {@code application}, not {@code infrastructure} (moved here in FASE 6): {@code
 * AskInsuranceKnowledgeUseCase}/{@code HybridRetrievalService} need this record's {@code rag}
 * section directly, and {@code application} may not depend on {@code infrastructure}
 * ({@code ArchitectureTest.applicationMustNotDependOnAdaptersOrInfrastructure}/{@code
 * hexagonalLayersRespectDependencyDirection}). A plain {@code @ConfigurationProperties} record
 * with no framework-specific behaviour is exactly the kind of type {@code application} is
 * already allowed to hold ({@code @Service}/{@code @Value} usage predates this class) - moving
 * it does not weaken any architecture rule, it corrects which layer actually owns this data.
 * {@code adapters}/{@code infrastructure} remain free to depend on it, since {@code adapters ->
 * application} and {@code infrastructure -> application} are both allowed dependency directions.
 */
@ConfigurationProperties(prefix = "insurance-ai")
public record InsuranceAiProperties(Rag rag, Security security, Governance governance, Ai ai, Evaluation evaluation) {

    public InsuranceAiProperties {
        Objects.requireNonNull(rag, "insurance-ai.rag must be configured");
        Objects.requireNonNull(security, "insurance-ai.security must be configured");
        Objects.requireNonNull(governance, "insurance-ai.governance must be configured");
        Objects.requireNonNull(ai, "insurance-ai.ai must be configured");
        Objects.requireNonNull(evaluation, "insurance-ai.evaluation must be configured");
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

        /**
         * PostgreSQL full-text search (BM25-style lexical retrieval). {@code minRank} is the
         * minimum {@code ts_rank_cd} a candidate must reach to count as lexical grounding
         * evidence in {@code AskInsuranceKnowledgeUseCase#hasQualifyingCandidate} (FASE 14 audit
         * remediation, see {@code docs/adr/ADR-012-AUDIT-REMEDIATION.md}) - {@code
         * PostgresLexicalSearchAdapter}'s {@code @@} match operator alone only proves "at least
         * one query term matched somewhere", not "this is a meaningfully relevant match".
         * Defaults to {@code 0.0} (every {@code @@} match still qualifies, i.e. the exact FASE
         * 6-13 behaviour) so introducing this field is not itself a behaviour change - a
         * production deployment should raise it once real {@code ts_rank_cd} distributions from
         * its own corpus have been observed, since the scale of that score depends on term
         * frequency and document length and cannot be usefully guessed in the abstract.
         */
        public record Lexical(int topK, double minRank) {
            public Lexical {
                if (topK <= 0) {
                    throw new IllegalArgumentException("insurance-ai.rag.lexical.top-k must be positive");
                }
                if (minRank < 0.0) {
                    throw new IllegalArgumentException("insurance-ai.rag.lexical.min-rank must not be negative");
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
     * FASE 10 (AI Evaluation) quality gate: the minimum each {@code EvaluationMetrics} ratio
     * must reach for {@code EvaluationRunnerService} to mark a run {@code PASSED} rather than
     * {@code FAILED} - the PoC's regression-detection mechanism (brief section 10's "thresholds,
     * regression detection"): a run whose retrieval/grounding quality has regressed below these
     * configured floors fails the gate immediately, without needing a second run to diff against.
     */
    public record Evaluation(Thresholds thresholds) {
        public Evaluation {
            Objects.requireNonNull(thresholds, "insurance-ai.evaluation.thresholds must be configured");
        }

        public record Thresholds(double minGroundingRate, double minNoAnswerAccuracy, double minRecallAtK) {
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

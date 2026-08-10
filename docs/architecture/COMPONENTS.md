# Components — Insurance Knowledge Assistant

Status: living document, created FASE 12 to fulfil `ARCHITECTURE.md`'s own forward reference. This
is an index into each bounded context's real classes - depth and rationale live in the dedicated
doc linked per section, not duplicated here (brief instruction: update/cross-reference existing
docs, don't re-explain them).

## 1. Document Management (`domain.document`, `application.document`)

- **Aggregate**: `Document` (root) → `DocumentVersion` → `DocumentChunk`. Version status lifecycle:
  `UPLOADED → PROCESSED → EMBEDDED` (or `FAILED`).
  See `docs/adr/ADR-003-DOCUMENT-AGGREGATE-BOUNDARIES.md`, `docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md`.
- **Inbound**: `DocumentController` (`/api/documents`).
- **Application**: `RegisterDocumentUseCase`, the Kafka-driven ingestion pipeline (extraction →
  chunking → embedding, `insurance.document.uploaded/processed/embedded`).
- **Outbound ports/adapters**: `DocumentRepository`/`JdbcDocumentRepository`,
  `DocumentChunkRepository`/`JdbcDocumentChunkRepository`, `PdfTextExtractorPort`/`PdfBoxTextExtractor`.

## 2. RAG (`domain.rag`, `application.rag`)

- **Orchestration**: `AskInsuranceKnowledgeUseCase` (top-level: guardrails → retrieval → grounding
  check → LLM → audit) delegates retrieval specifically to `HybridRetrievalService` (query
  expansion → parallel semantic+lexical search → RRF fusion → reranking → context selection).
  See `docs/rag/RAG_DESIGN.md`, `docs/rag/HYBRID_SEARCH.md`, `docs/rag/RERANKING.md`,
  `docs/rag/EMBEDDINGS.md`, `docs/adr/ADR-005-EMBEDDING-AS-DERIVED-PROJECTION.md`,
  `docs/adr/ADR-006-ADVANCED-RAG-RETRIEVAL-STRATEGY.md`.
- **Inbound**: `ChatController` (`/api/chat`).
- **Outbound ports/adapters**: `EmbeddingModelPort`/`OpenAiEmbeddingAdapter`/`FakeEmbeddingModelAdapter`,
  `VectorSearchPort`/`PgVectorStoreAdapter`, `LexicalSearchPort`/`PostgresLexicalSearchAdapter`,
  `RerankerPort`/`RuleBasedReranker` (explicitly PoC-level, not ML-based - see `docs/rag/RERANKING.md`),
  `LlmProvider`/`OpenAiLlmAdapter`/`AnthropicLlmAdapter`/`FakeLlmAdapter`.
- **No-answer policy**: grounded only if a final candidate's `semanticScore` clears the configured
  threshold or has a non-null `lexicalScore` - never derived from fusion/reranker scores. No
  qualifying candidate → the LLM is never called.

## 3. GenAI Security (`domain.security`, `application.security`)

- **Application**: `InputGuardService` - assesses the question (prompt injection blocks the
  request; PII does not block, only redacted-logs); separately scans retrieved chunk content
  (log-only, the real defense is `LlmMessageFormatter`'s structural system/user separation) and the
  generated answer (log-only PII detection).
- **Outbound ports/adapters**: `PromptInjectionGuardPort`/`RuleBasedPromptInjectionGuard`,
  `PiiGuardPort`/`RuleBasedPiiGuard` - both explicitly regex/rule-based, documented as PoC-level,
  never presented as production ML security. See `docs/security/SECURITY.md`,
  `docs/security/PROMPT_INJECTION.md`, `docs/security/PII.md`, `docs/adr/ADR-007-GENAI-SECURITY-GUARDRAILS.md`.
- No dedicated REST surface - invoked inline by `AskInsuranceKnowledgeUseCase`.

## 4. AI Governance (`domain.aisystem`/`domain.model`/`domain.prompt`/`domain.governance`)

- **Aggregates**: `AiSystem` (registry entry, human oversight requirement), `AiModel` (provider
  allow-list entry), `Prompt` (versioned, checksummed, at-most-one-`ACTIVE`-per-key), `RiskAssessment`
  (documented classification rationale).
- **Inbound**: `GovernanceController` (`/api/governance`).
- **Application**: `AiSystemRegistryService`, `ModelRegistryService`, `PromptRegistryService`,
  `RiskAssessmentService`. `ModelRegistrySeeder` (infrastructure) registers the actually-configured
  provider at startup.
- **Outbound ports/adapters**: `AiSystemRepository`/`ModelRepository`/`PromptRepository`/
  `RiskAssessmentRepository`, each with a plain-JDBC adapter.
- See `docs/governance/AI_GOVERNANCE.md`, `AI_ACT.md`, `HUMAN_OVERSIGHT.md`,
  `docs/adr/ADR-008-AI-GOVERNANCE-FOUNDATION.md`.

## 5. AI Audit (`domain.audit`, `application.audit`)

- **Aggregate**: `AuditRecord` - immutable, append-only, one row per `POST /api/chat` execution
  regardless of outcome (grounded/no-answer/blocked/error). Deliberately excludes raw question/
  answer/chunk text (data minimization).
- **Inbound**: `AuditController` (`/api/audit`, read-only).
- **Application**: `AuditService`.
- **Outbound**: `AuditRepository`/`JdbcAuditRepository`.
- See `docs/audit/AI_AUDIT.md`.

## 6. AI Evaluation (`domain.evaluation`, `application.evaluation`)

- **Aggregate**: `EvaluationRun` (immutable, one per dataset run) containing `EvaluationCaseResult`
  values and computed `EvaluationMetrics` (Recall@K, MRR, grounding rate, no-answer accuracy,
  citation coverage - Precision@K explicitly not computed, see that class's Javadoc).
- **Inbound**: `EvaluationController` (`/api/evaluation`).
- **Application**: `EvaluationRunnerService` (runs `InsuranceEvaluationDataset`'s built-in cases
  through the real `AskInsuranceKnowledgeUseCase`, gates `PASSED`/`FAILED` against configured
  thresholds).
- **Outbound**: `EvaluationRunRepository`/`JdbcEvaluationRunRepository`.
- See `docs/evaluation/AI_EVALUATION.md`, `docs/adr/ADR-009-AI-EVALUATION-FOUNDATION.md`.

## 7. Cross-cutting (`infrastructure`, `domain.shared`)

- **Exception hierarchy**: `DomainException`/`ApplicationException`/`InfrastructureException`
  (split `TransientProcessingException`/`PermanentProcessingException`) - `GlobalExceptionHandler`
  maps each to a distinct HTTP status (422/422/503/502). See
  `docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md`, `docs/resilience/RESILIENCE.md`.
- **Traceability**: `TraceIdFilter` (servlet filter, MDC) + `TraceId` (`domain.shared`) - the same
  identifier flows through logs, `AuditRecord`, `EvaluationCaseResult`, and every API response.
- **Kafka resilience**: `KafkaErrorHandlingConfiguration` (`DefaultErrorHandler`,
  `DeadLetterPublishingRecoverer`, exponential backoff).
- **Observability**: `spring-boot-starter-actuator` (`/actuator/health`, `/actuator/metrics`),
  custom `rag.retrieval.latency`/`rag.llm.latency` timers. See `docs/observability/OBSERVABILITY.md`.
- **API documentation**: `OpenApiConfiguration` + springdoc-openapi (`/v3/api-docs`,
  `/swagger-ui.html`). See `docs/adr/ADR-011-API-DOCUMENTATION.md`.

## 8. What is intentionally not a "component"

Per `docs/architecture/ARCHITECTURE.md` section 7: there is no risk-assessment/pricing/eligibility/
claims-decision component anywhere in this codebase - not stubbed, not disabled, not planned. Its
absence is the architectural guarantee, not an oversight.

# Insurance Knowledge Assistant

> **THIS IS AN ENTERPRISE-GRADE ARCHITECTURAL PoC, NOT A PRODUCTION CERTIFICATION.**
> It demonstrates how an insurance company could introduce Generative AI in a controlled,
> traceable, secure and governed way - DDD + Hexagonal Architecture, real AI Governance/Audit/
> Evaluation bounded contexts, GenAI security guardrails - over a real PostgreSQL/pgvector/Kafka
> stack. It has **not** undergone legal, compliance, security, or penetration-testing review. See
> [Production Gap Analysis](#production-gap-analysis) below for exactly what that means.

A Retrieval-Augmented Generation (RAG) assistant that answers employee questions about ingested
insurance documentation - **strictly grounded in retrieved content, with citations, and never
making a claims/pricing/eligibility/underwriting decision.** The system informs; a human always
decides (see `docs/governance/HUMAN_OVERSIGHT.md`).

## Tech stack

- Java 25 (Microsoft Build of OpenJDK 25.0.4 LTS) + Spring Boot 4.1.0 + Spring AI 2.0.0
- PostgreSQL 16 + pgvector (semantic search) + PostgreSQL full-text search (lexical search) +
  Reciprocal Rank Fusion + reranking - see `docs/rag/HYBRID_SEARCH.md`
- Kafka (KRaft mode) for asynchronous document ingestion
- Flyway (sole schema owner - `src/main/resources/db/migration`)
- DDD + Hexagonal Architecture (Ports & Adapters), Modular Monolith, boundaries enforced by
  ArchUnit (`ArchitectureTest`) - see `docs/adr/ADR-001-HEXAGONAL-ARCHITECTURE.md`
- OpenAI and Anthropic as pluggable LLM providers (`LlmProvider` port), plus a deterministic
  `fake` provider for offline testing/demo - never presented as a real provider

## Architecture at a glance

```
Employee → guardrails (prompt injection / PII) → hybrid retrieval (semantic + lexical, RRF,
  reranking) → grounding check → Prompt Registry → LLM (OpenAI/Anthropic/fake) → grounded answer
  + citations + traceId → AI Audit
```

Full detail: `docs/architecture/ARCHITECTURE.md`, `docs/architecture/C4.md`,
`docs/architecture/COMPONENTS.md`. Every architectural decision that could plausibly be
questioned later has a numbered ADR under `docs/adr/` (currently ADR-001 through ADR-011).

## Bounded contexts

| Context | What it owns |
|---|---|
| Document Management | Documents, versions, chunks, metadata, async ingestion via Kafka |
| RAG | Hybrid retrieval, reranking, context assembly, grounding, citations |
| GenAI Security | Prompt injection guard, PII guard (rule-based, honestly labelled PoC-level) |
| AI Governance | AI System Registry, Model Registry, Prompt Registry, Risk Assessment, Human Oversight |
| AI Audit | Immutable, data-minimized execution log for every request |
| AI Evaluation | Regression dataset, metrics (Recall@K, MRR, grounding rate, ...), quality gate |

## Quick start

**1. Prerequisites**: Docker, Java 25, Maven Wrapper (`./mvnw`, bundled).

**2. Start local infrastructure** (PostgreSQL + pgvector, Kafka, Kafka UI - nothing else; no
Elasticsearch/Redis/API Gateway, see `docker-compose.yml`):

```bash
docker compose up -d
```

- PostgreSQL: `localhost:5433` (mapped from the container's `5432`)
- Kafka: `localhost:9094` (external listener, for the app running on the host)
- Kafka UI: `http://localhost:8081`

**3. Configure credentials** (optional for a fake-provider demo):

```bash
cp .env.example .env
# fill in OPENAI_API_KEY / ANTHROPIC_API_KEY only if you want a real provider call
```

**4. Run the application** (Flyway migrates the schema automatically on startup):

```bash
./mvnw spring-boot:run
# or, to run without any real LLM credentials:
INSURANCE_AI_PROVIDER=fake ./mvnw spring-boot:run
```

**5. Explore the API**: `http://localhost:8080/swagger-ui.html` (OpenAPI docs, generated from the
real controllers - see `docs/adr/ADR-011-API-DOCUMENTATION.md`). Health check:
`http://localhost:8080/actuator/health`.

**6. Run the full test suite** (unit + real-Postgres/Kafka integration via Testcontainers):

```bash
./mvnw clean verify
```

See `docs/testing/TESTCONTAINERS.md` for why containers are reused across the suite and how
per-test isolation is achieved without starting a new container per test class.

## API overview

| Base path | Purpose |
|---|---|
| `POST /api/chat` | Ask a question - the main RAG endpoint |
| `POST /api/documents`, `GET /api/documents/{id}` | Upload a PDF, check ingestion status |
| `GET/POST /api/governance/**` | AI System/Model/Prompt Registry, Risk Assessments |
| `GET /api/audit/**` | Look up the audit record for a request by `traceId` |
| `POST/GET /api/evaluation/**` | Run and inspect the built-in evaluation dataset |

Full request/response shapes: `/swagger-ui.html` once the app is running. A minimal example:

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "Is water damage from a burst pipe covered?"}'
```

The response always carries a `traceId` and a `grounding.status` (`GROUNDED`/`NOT_GROUNDED`) - a
`NOT_GROUNDED` response with a 200 status is a deliberate, explicit no-answer (or a blocked
prompt-injection attempt), never a fabricated answer (see `docs/rag/RAG_DESIGN.md` section on the
no-answer policy).

## Demo data

Document types accepted by `POST /api/documents`: `type` is one of `POLICY`,
`CLAIMS_PROCEDURE`, `CORPORATE`; `classification` is one of `PUBLIC`, `INTERNAL`,
`CONFIDENTIAL`, `RESTRICTED`. `docs/demo/DEMO_GUIDE.md` (FASE 13) walks through a full upload →
ask → citation → audit trace demo end to end.

## Documentation map

- **Architecture**: `docs/architecture/ARCHITECTURE.md`, `C4.md`, `COMPONENTS.md`
- **RAG design**: `docs/rag/RAG_DESIGN.md`, `HYBRID_SEARCH.md`, `RERANKING.md`, `EMBEDDINGS.md`
- **Security**: `docs/security/SECURITY.md`, `PROMPT_INJECTION.md`, `PII.md`
- **Governance**: `docs/governance/AI_GOVERNANCE.md`, `AI_ACT.md`, `HUMAN_OVERSIGHT.md`
- **Audit**: `docs/audit/AI_AUDIT.md`
- **Evaluation**: `docs/evaluation/AI_EVALUATION.md`
- **Observability & Resilience**: `docs/observability/OBSERVABILITY.md`, `docs/resilience/RESILIENCE.md`
- **Testing strategy**: `docs/testing/TESTCONTAINERS.md`
- **Every architectural decision**: `docs/adr/ADR-001` through `ADR-011`

## Production Gap Analysis

Explicitly out of scope for this PoC (not implemented, not simulated as implemented):

IAM/authentication/authorization on any endpoint (including `/api/governance/**` and
`/actuator/**`), API Gateway, rate limiting, WAF, SIEM integration, distributed tracing beyond the
custom `traceId` MDC correlation, managed/HA PostgreSQL and Kafka, backup/disaster recovery, model
risk management, third-party model monitoring, formal legal/compliance/DPO review of the AI Act
self-assessment (`docs/governance/AI_ACT.md`), independent security review or penetration testing,
data retention policy, and a formal incident response process. Every fake/heuristic component
(`FakeLlmAdapter`, `FakeEmbeddingModelAdapter`, `RuleBasedPromptInjectionGuard`,
`RuleBasedPiiGuard`, `RuleBasedReranker`) is documented as such in its own Javadoc and in the
corresponding `docs/` file - never presented as a production-grade equivalent.

## Configuration reference

Typed configuration lives under `insurance-ai.*` (`InsuranceAiProperties`,
`application.configuration`) - see `application.yaml` for the full annotated list (RAG retrieval
tuning, security guardrail toggles, evaluation quality-gate thresholds, LLM provider selection).
Standard Spring properties (`spring.datasource.*`, `spring.kafka.*`, `spring.ai.openai/anthropic.*`,
`spring.http.clients.*`, `management.*`) follow normal Spring Boot conventions. `.env.example`
documents every environment variable actually read.

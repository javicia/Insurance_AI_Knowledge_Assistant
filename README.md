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
decides (see `docs/governance/HUMAN_OVERSIGHT.md`). A single-page Angular UI (chat, document
upload/status, and read views onto governance/audit/evaluation) is packaged into the same Spring
Boot process - see [Frontend](#frontend) below.

## Tech stack

- Java 25 (Microsoft Build of OpenJDK 25.0.4 LTS) + Spring Boot 4.1.0 + Spring AI 2.0.0
- Angular 22 (standalone components, signals, no NgRx) + Angular Material - see
  `docs/adr/ADR-013-FRONTEND-ARCHITECTURE.md`
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
questioned later has a numbered ADR under `docs/adr/` (currently ADR-001 through ADR-013).

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

Two ways to run this: the full packaged product in one container (fastest way to see the whole
thing, including the Angular UI), or the application on the host against containerized
infrastructure only (the day-to-day development loop). Both use the same `docker-compose.yml`.

### Option A - full product in Docker (UI included, no local Node/Angular toolchain needed)

**Prerequisites**: Docker only.

```bash
cp .env.example .env   # defaults already work with the fake provider, no real API keys needed
docker compose up -d --build
```

This starts PostgreSQL, Kafka, Kafka UI, and `insurance-ai` (the Spring Boot process with the
Angular build packaged into its static resources - see [Frontend](#frontend) and
[Single-container deployment](#single-container-deployment) below). Once
`docker compose ps` shows `insurance-ai-app` as `healthy`:

- **Application (UI + API)**: `http://localhost:8080/` (redirects into the Angular app)
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Kafka UI: `http://localhost:8081`

### Option B - application on the host, infrastructure in Docker (development loop)

**1. Prerequisites**: Docker, Java 25, Maven Wrapper (`./mvnw`, bundled). Node.js 20+ only if
you're also working on the frontend (see [Frontend](#frontend)).

**2. Start local infrastructure** (PostgreSQL + pgvector, Kafka, Kafka UI - nothing else; no
Elasticsearch/Redis/API Gateway, see `docker-compose.yml`):

```bash
docker compose up -d postgres kafka kafka-ui
```

- PostgreSQL: `localhost:5433` (mapped from the container's `5432`)
- Kafka: `localhost:9094` (external listener, for the app running on the host)
- Kafka UI: `http://localhost:8081`

**3. Configure credentials** (optional for a fake-provider demo):

```bash
cp .env.example .env
# fill in OPENAI_API_KEY / ANTHROPIC_API_KEY only if you want a real provider call
```

**4. Run the backend** (Flyway migrates the schema automatically on startup):

```bash
./mvnw spring-boot:run
# or, to run without any real LLM credentials:
INSURANCE_AI_PROVIDER=fake ./mvnw spring-boot:run
```

**5. Run the frontend** (separate terminal - `proxy.conf.json` forwards `/api`/`/actuator` to
`:8080`, so both dev servers work against the same backend):

```bash
cd frontend
npm install
npm start   # http://localhost:4200, live-reloading
```

**6. Explore the API**: `http://localhost:8080/swagger-ui.html` (OpenAPI docs, generated from the
real controllers - see `docs/adr/ADR-011-API-DOCUMENTATION.md`). Health check:
`http://localhost:8080/actuator/health`.

**7. Run the test suites**:

```bash
./mvnw clean verify              # backend: unit + real-Postgres/Kafka integration (Testcontainers)
cd frontend && npm test -- --watch=false   # frontend: Vitest unit/component/interceptor tests
```

See `docs/testing/TESTCONTAINERS.md` for why backend containers are reused across the suite and
how per-test isolation is achieved without starting a new container per test class.

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

## Frontend

`frontend/` is a standalone Angular 22 application (standalone components, signals, Angular
Material, Vitest) covering five routes: the Assistant chat (home screen), Documents
(upload + session-tracked ingestion status), and read-only Governance/Audit/Evaluation views onto
the backend's existing endpoints. It calls the backend exclusively through `/api/**` (relative
paths, same origin) - no hardcoded host/port, no CORS configuration needed in any deployment mode.
See `docs/frontend/FRONTEND_ARCHITECTURE.md` (directory structure, state management, testing),
`docs/frontend/UI_GUIDELINES.md` (design tokens, components, accessibility), and
`docs/adr/ADR-013-FRONTEND-ARCHITECTURE.md` (why Angular/Material/signals/single-container, not
alternatives).

## Single-container deployment

The Docker image (`Dockerfile`, multi-stage) compiles the Angular app (`npm ci && npm run build`),
copies its output into `src/main/resources/static/`, then builds the Spring Boot jar - so the
final image is one process serving both the UI and the API on port `8080`, with no reverse proxy,
no second exposed port, and no CORS configuration. `SpaWebConfiguration` resolves any
non-API/non-actuator path to `index.html` so Angular's router handles client-side navigation and
direct URL refresh (`/assistant`, `/documents`, ...) correctly, while an unmapped `/api/**` path
still gets a real `404`/`405` rather than silently serving HTML. `./mvnw clean verify` itself never
invokes Node - the frontend build only happens inside the Docker image build - so backend-only
contributors and CI are unaffected by the frontend toolchain.

## Demo data

Document types accepted by `POST /api/documents`: `type` is one of `POLICY`,
`CLAIMS_PROCEDURE`, `CORPORATE`; `classification` is one of `PUBLIC`, `INTERNAL`,
`CONFIDENTIAL`, `RESTRICTED`. `docs/demo/DEMO_GUIDE.md` (FASE 13) walks through a full upload →
ask → citation → audit trace demo end to end.

## Documentation map

- **Architecture**: `docs/architecture/ARCHITECTURE.md`, `C4.md`, `COMPONENTS.md`
- **Frontend**: `docs/frontend/FRONTEND_ARCHITECTURE.md`, `UI_GUIDELINES.md`
- **RAG design**: `docs/rag/RAG_DESIGN.md`, `HYBRID_SEARCH.md`, `RERANKING.md`, `EMBEDDINGS.md`
- **Security**: `docs/security/SECURITY.md`, `PROMPT_INJECTION.md`, `PII.md`
- **Governance**: `docs/governance/AI_GOVERNANCE.md`, `AI_ACT.md`, `HUMAN_OVERSIGHT.md`
- **Audit**: `docs/audit/AI_AUDIT.md`
- **Evaluation**: `docs/evaluation/AI_EVALUATION.md`
- **Observability & Resilience**: `docs/observability/OBSERVABILITY.md`, `docs/resilience/RESILIENCE.md`
- **Testing strategy**: `docs/testing/TESTCONTAINERS.md` (backend); frontend testing is covered in
  `docs/frontend/FRONTEND_ARCHITECTURE.md` section 11 rather than a separate file
- **Demo / manual E2E walkthrough**: `docs/demo/DEMO_GUIDE.md`, `FINAL_FRONTEND_AUDIT.md`
- **Every architectural decision**: `docs/adr/ADR-001` through `ADR-013`

There is no separate `docs/DEVELOPMENT.md`/`docs/DEPLOYMENT.md`/`docs/TESTING.md`: this README's
Quick Start (both run modes), Single-container deployment, and per-suite test commands already
cover that ground without duplicating it, consistent with this project's existing pattern of one
focused doc per topic under `docs/<topic>/` rather than a second, competing top-level index.

## Production Gap Analysis

Explicitly out of scope for this PoC (not implemented, not simulated as implemented):

IAM/authentication/authorization on any endpoint (including `/api/governance/**`, `/api/audit/**`,
`/actuator/**`, `/swagger-ui.html`, `/v3/api-docs`, and the Angular UI itself - it is not
behind a login), API Gateway, rate limiting, WAF, SIEM integration, distributed tracing beyond the
custom `traceId` MDC correlation (now also surfaced in the Angular UI's `TechnicalDetails`
component), managed/HA PostgreSQL and Kafka, backup/disaster recovery, model risk management,
third-party model monitoring, formal legal/compliance/DPO review of the AI Act self-assessment
(`docs/governance/AI_ACT.md`), independent security review or penetration testing, data retention
policy, a formal incident response process, i18n/dark mode, and an automated browser E2E suite
(Playwright/Cypress - E2E validation is manual/scripted against the real Docker Compose stack, see
`FINAL_FRONTEND_AUDIT.md`). Every fake/heuristic component (`FakeLlmAdapter`,
`FakeEmbeddingModelAdapter`, `RuleBasedPromptInjectionGuard`, `RuleBasedPiiGuard`,
`RuleBasedReranker`) is documented as such in its own Javadoc and in the corresponding `docs/`
file - never presented as a production-grade equivalent.

## Configuration reference

Typed configuration lives under `insurance-ai.*` (`InsuranceAiProperties`,
`application.configuration`) - see `application.yaml` for the full annotated list (RAG retrieval
tuning, security guardrail toggles, evaluation quality-gate thresholds, LLM provider selection).
Standard Spring properties (`spring.datasource.*`, `spring.kafka.*`, `spring.ai.openai/anthropic.*`,
`spring.http.clients.*`, `management.*`) follow normal Spring Boot conventions. `.env.example`
documents every environment variable actually read.

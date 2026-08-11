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
upload/status, and read views onto governance/audit/evaluation) is a fully independent deployable
served behind its own API Gateway - see [Frontend](#frontend) and
[Architecture: three independent deployables](#architecture-three-independent-deployables) below.

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

## Architecture: three independent deployables

Since FASE 16 (`docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md`), the frontend, backend, and API
gateway are three genuinely separate products - separate Maven/npm projects, separate Docker
images, no shared code, no shared build, communicating only over HTTP/REST:

```
Browser → frontend (nginx, static Angular bundle)
Browser → gateway (Spring Cloud Gateway) → backend (Spring Boot API) → PostgreSQL/Kafka/LLM
```

The frontend's JavaScript calls the gateway directly (`PUBLIC_API_BASE_URL`, injected at container
startup - see [Frontend](#frontend)); the gateway is the only path from the browser to the backend
API in the Docker Compose topology (the backend's own port is also published for direct developer
access - see [Quick start](#quick-start) Option B). `scripts/verify-module-separation.sh` is an
automated, CI-runnable check that the three deployables stay genuinely independent.

## RAG pipeline at a glance

```
Employee → guardrails (prompt injection / PII) → hybrid retrieval (semantic + lexical, RRF,
  reranking) → grounding check → Prompt Registry → LLM (OpenAI/Anthropic/fake) → grounded answer
  + citations + traceId → AI Audit
```

Full detail: `docs/architecture/ARCHITECTURE.md`, `docs/architecture/C4.md`,
`docs/architecture/COMPONENTS.md`. Every architectural decision that could plausibly be
questioned later has a numbered ADR under `docs/adr/` (currently ADR-001 through ADR-014).

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

Two ways to run this: the full product in Docker (all six services - fastest way to see the whole
thing, including the Angular UI behind the gateway), or the backend/frontend on the host against
containerized infrastructure only (the day-to-day development loop). Both use the same
`docker-compose.yml`.

### Option A - full product in Docker (no local Node/Java toolchain needed)

**Prerequisites**: Docker only.

```bash
cp .env.example .env   # defaults already work with the fake provider, no real API keys needed
docker compose up -d --build
```

This builds and starts all six services: `postgres`, `kafka`, `kafka-ui`, `backend` (Spring Boot
API), `gateway` (Spring Cloud Gateway), and `frontend` (nginx serving the Angular build) - see
[Architecture: three independent deployables](#architecture-three-independent-deployables). Once
`docker compose ps` shows all of them `healthy`:

- **Application (UI)**: `http://localhost:8083/` - the frontend calls the gateway directly from
  the browser
- **API (via gateway)**: `http://localhost:8082/api/**`
- **Backend directly** (developer convenience - Swagger UI, `curl`): `http://localhost:8080`,
  `http://localhost:8080/swagger-ui.html`
- Kafka UI: `http://localhost:8081`

### Option B - backend/frontend on the host, infrastructure in Docker (development loop)

**1. Prerequisites**: Docker, Java 25, Maven Wrapper (`./mvnw`, bundled, under `backend/`).
Node.js 20+ only if you're also working on the frontend (see [Frontend](#frontend)).

**2. Start local infrastructure** (PostgreSQL + pgvector, Kafka, Kafka UI - nothing else; no
Elasticsearch/Redis, see `docker-compose.yml`):

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
cd backend
./mvnw spring-boot:run
# or, to run without any real LLM credentials:
INSURANCE_AI_PROVIDER=fake ./mvnw spring-boot:run
```

**5. Run the frontend** (separate terminal - `proxy.conf.json` forwards `/api`/`/actuator` to
`:8080`, so `ng serve` talks directly to the host-run backend without needing the gateway for local
iteration):

```bash
cd frontend
npm install
npm start   # http://localhost:4200, live-reloading
```

**6. (Optional) Run the gateway too**, if you're specifically iterating on gateway behavior
(routing, CORS, rate limiting):

```bash
cd gateway
./mvnw spring-boot:run
# gateway on :8082, routing to the backend on :8080
```

**7. Explore the API**: `http://localhost:8080/swagger-ui.html` (OpenAPI docs, generated from the
real controllers - see `docs/adr/ADR-011-API-DOCUMENTATION.md`). Health check:
`http://localhost:8080/actuator/health`.

**8. Run the test suites**:

```bash
cd backend && ./mvnw clean verify           # unit + real-Postgres/Kafka integration (Testcontainers)
cd frontend && npm test -- --watch=false    # Vitest unit/component/interceptor tests
cd gateway && ./mvnw clean verify           # routing/CORS/correlation-id tests
./scripts/verify-module-separation.sh       # confirms no cross-module coupling regressed
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

Full request/response shapes: `/swagger-ui.html` once the app is running. A minimal example
(directly against the backend; replace `8080` with `8082` to go through the gateway instead):

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
the backend's existing endpoints. Since FASE 16, it is a fully independent deployable: its own
Dockerfile builds the Angular bundle and serves it from `nginxinc/nginx-unprivileged` - no JVM, no
Maven, no shared build with the backend. It knows exactly one external fact,
`PUBLIC_API_BASE_URL` (the gateway's browser-reachable URL), injected at container startup by
`docker-entrypoint.sh` into a generated `env.js` - never a database URL, Kafka URL, LLM credential,
or internal service hostname, and never baked into the build (the same image is deployable against
any environment's gateway URL without a rebuild). See `docs/frontend/FRONTEND_ARCHITECTURE.md`
(directory structure, state management, testing), `docs/frontend/UI_GUIDELINES.md` (design tokens,
components, accessibility), `docs/adr/ADR-013-FRONTEND-ARCHITECTURE.md` (why Angular/Material/
signals), and `docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md` (why it is now independently
deployed, superseding ADR-013's single-container packaging).

## API Gateway

`gateway/` is a genuinely separate Spring Cloud Gateway (WebFlux) Maven module and Docker image -
it shares no code with `backend/`, only an HTTP contract. It routes `/api/chat/**`,
`/api/documents/**`, `/api/governance/**`, `/api/audit/**`, and `/api/evaluation/**` to the
backend, enforces CORS against the frontend's origin, and assigns `X-Trace-Id` at the true edge of
the system if a client didn't already supply one (the backend's own `TraceIdFilter` reuses whatever
value it receives, so one trace id covers the full journey). See
`docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md` decision 7; deeper gateway hardening (rate
limiting, JWT propagation) is tracked as FASE 19/21 in `ENTERPRISE_HARDENING_DISCOVERY.md`.

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

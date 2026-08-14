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
[Architecture](#architecture-independent-deployables-behind-a-single-edge) below.

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

## Architecture: independent deployables behind a single edge

Since FASE 16 (`docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md`), the frontend, backend, and API
gateway are genuinely separate products - separate Maven/npm projects, separate Docker images, no
shared code, no shared build, communicating only over HTTP/REST. FASE 17-21 added a real IAM
(Keycloak, OAuth2/OIDC) and a WAF (ModSecurity + OWASP CRS) as the actual single browser-facing
edge - the gateway is no longer reached directly by the browser:

```
Browser → WAF (ModSecurity/OWASP CRS, the one public edge)
            ├── / → frontend (nginx, static Angular bundle)
            └── /api/** → gateway (Spring Cloud Gateway, JWT validation, rate limiting)
                              → backend (Spring Boot API) → PostgreSQL/Kafka/LLM
                              ↘ Keycloak (OAuth2/OIDC, independent deployable)

backend/gateway ─(OTLP + syslog)─→ otel-collector ─→ Jaeger (trace storage/UI)
                                                  └─→ [security events; SIEM-ready seam - see
                                                       docs/security/SIEM.md]
```

`PUBLIC_API_BASE_URL` (injected into the frontend container at startup - see
[Frontend](#frontend)) points at the WAF, not the gateway. The gateway's and backend's own ports
are also published for direct developer access (curl/Swagger) - see [Quick start](#quick-start)
Option B - but real browser traffic in the Docker Compose topology only ever goes through the
WAF. `scripts/verify-module-separation.sh` is an automated, CI-runnable check that the deployables
stay genuinely independent. See `docs/adr/ADR-016-WAF-EDGE.md` and
`docs/security/IAM_ARCHITECTURE.md` for the full reasoning. Distributed tracing (FASE 25 - real
OpenTelemetry spans, W3C `traceparent` propagation, an OTel Collector, Jaeger) is documented
separately in `docs/observability/DISTRIBUTED_TRACING.md` - see that document before assuming the
`X-Trace-Id` header/MDC correlation ID mentioned elsewhere in this README is the same thing (it is
not; both exist side by side, see that document section 4 for exactly why).

## RAG pipeline at a glance

```
Employee → guardrails (prompt injection / PII) → hybrid retrieval (semantic + lexical, RRF,
  reranking) → grounding check → Prompt Registry → LLM (OpenAI/Anthropic/fake) → grounded answer
  + citations + traceId → AI Audit
```

Full detail: `docs/architecture/ARCHITECTURE.md`, `docs/architecture/C4.md`,
`docs/architecture/COMPONENTS.md`. Every architectural decision that could plausibly be
questioned later has a numbered ADR under `docs/adr/` (currently ADR-001 through ADR-016).

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

Two ways to run this: the full product in Docker (all ten services - fastest way to see the
whole thing, including IAM login, the WAF edge, and distributed tracing), or the backend/frontend
on the host against containerized infrastructure only (the day-to-day development loop). Both use
the same `docker-compose.yml`.

### Option A - full product in Docker (no local Node/Java toolchain needed)

**Prerequisites**: Docker only.

```bash
cp .env.example .env   # defaults already work with the fake provider, no real API keys needed
docker compose up -d --build
```

This builds and starts all ten services: `postgres`, `kafka`, `kafka-ui`, `keycloak` (OAuth2/
OIDC IAM), `backend` (Spring Boot API), `gateway` (Spring Cloud Gateway), `waf` (ModSecurity/OWASP
CRS, the one public edge), `frontend` (nginx serving the Angular build), `otel-collector`
(OpenTelemetry Collector), and `jaeger` (trace storage/UI) - see
[Architecture](#architecture-independent-deployables-behind-a-single-edge) and
`docs/observability/DISTRIBUTED_TRACING.md`. Once `docker compose ps` shows every *healthcheck-
bearing* service `healthy` (`kafka-ui` has no healthcheck defined - it stays `Up`, not `healthy`;
that is expected, not a defect, see docs/observability/DISTRIBUTED_TRACING.md's evidence section
for the full per-service healthcheck inventory) - Keycloak's own Quarkus startup can take up to
~15 minutes on a slow disk, see `docs/testing/TESTCONTAINERS.md` and
`KeycloakJwtValidationTest`'s Javadoc for the empirical measurement:

- **Application (UI) and API - through the WAF, the real browser-facing edge**:
  `http://localhost:8000/`
- **Keycloak** (login UI, realm admin): `http://localhost:8180`
- **Jaeger UI** (distributed trace search/visualization): `http://localhost:16686`
- **Gateway directly** (developer convenience, bypasses the WAF): `http://localhost:8082/api/**`
- **Backend directly** (developer convenience - Swagger UI, `curl`, bypasses gateway+WAF):
  `http://localhost:8080`, `http://localhost:8080/swagger-ui.html`
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
cd frontend && npx ng test --watch=false    # Vitest unit/component/interceptor tests
cd gateway && ./mvnw clean verify           # routing/CORS/correlation-id/trace-propagation tests
./scripts/verify-module-separation.sh       # confirms no cross-module coupling regressed
```

**Browser E2E (Playwright)** - runs against the *running* Docker stack, through the WAF:

```bash
docker compose up -d                        # the suite does not start the stack itself
cd e2e && npm install && npx playwright install chromium
npx playwright test
```

See `docs/testing/TESTCONTAINERS.md` for why backend containers are reused across the suite and
how per-test isolation is achieved without starting a new container per test class, and
`docs/testing/E2E_PLAYWRIGHT.md` for why the E2E suite targets the WAF rather than the gateway or
backend directly - including the six real deployment defects that decision surfaced, none of which
any non-browser check could see.

**Corpus funcional en español** (`test-data/insurance/auto/`) - 25 documentos ficticios de seguro
de automóvil (115 páginas, ~48.000 palabras) y 247 casos de prueba, para ejercitar el pipeline RAG
con documentación realista en lugar de fixtures de una frase:

```bash
python scripts/build_test_corpus_pdfs.py --check   # Markdown -> PDF, verifica la extracción
bash scripts/ingest_test_corpus.sh                 # ingesta real a través del WAF
python scripts/validate_test_corpus.py             # consistencia del corpus y del dataset
python scripts/run_functional_probe.py             # ejecuta casos contra el stack
```

Guía completa en español: `docs/testing/GUIA_PRUEBAS_FUNCIONALES_ES.md`. El corpus destapó un fallo
de seguridad real - el guardarraíl de inyección de prompts sólo tenía patrones en inglés, así que
las inyecciones en español lo atravesaban - y documenta con mediciones por qué el proveedor
offline `fake` no alcanza el umbral semántico de producción; ver
`docs/testing/INSURANCE_AUTO_TEST_CORPUS_REPORT_ES.md`.

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
`PUBLIC_API_BASE_URL` (the WAF's browser-reachable URL, the real edge since FASE 20 - see
`docs/adr/ADR-016-WAF-EDGE.md`), injected at container startup by `docker-entrypoint.sh` into a
generated `env.js` - never a database URL, Kafka URL, LLM credential, or internal service
hostname, and never baked into the build (the same image is deployable against any environment's
edge URL without a rebuild). See `docs/frontend/FRONTEND_ARCHITECTURE.md` (directory structure,
state management, testing), `docs/frontend/UI_GUIDELINES.md` (design tokens, components,
accessibility), `docs/adr/ADR-013-FRONTEND-ARCHITECTURE.md` (why Angular/Material/signals), and
`docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md` (why it is now independently deployed,
superseding ADR-013's single-container packaging).

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
- **Every architectural decision**: `docs/adr/ADR-001` through `ADR-016`

There is no separate `docs/DEVELOPMENT.md`/`docs/DEPLOYMENT.md`/`docs/TESTING.md`: this README's
Quick Start (both run modes) and per-suite test commands already cover that ground without
duplicating it, consistent with this project's existing pattern of one focused doc per topic under
`docs/<topic>/` rather than a second, competing top-level index.

## Production Gap Analysis

**Implemented and real** (not simulated, not stubbed - see the dedicated docs for exactly what
each one does and does not cover): IAM/authentication/authorization on every `/api/**` endpoint via
Keycloak OAuth2/OIDC + Spring Security, re-validated independently at both the gateway and the
backend (`docs/security/IAM_ARCHITECTURE.md`); a real API Gateway (Spring Cloud Gateway) with
per-identity/per-route rate limiting (`docs/architecture/API_GATEWAY.md`); a real WAF (ModSecurity
+ OWASP CRS) as the single browser-facing edge (`docs/adr/ADR-016-WAF-EDGE.md`); **real distributed
tracing** (FASE 25) - OpenTelemetry spans via `micrometer-tracing-bridge-otel`, W3C `traceparent`
propagation across WAF→Gateway→Backend, an OpenTelemetry Collector, and Jaeger for trace storage/
visualization (`docs/observability/DISTRIBUTED_TRACING.md` - covers gateway/backend HTTP, JDBC, and
Kafka producer/consumer spans; explicitly does **not** cover the WAF hop itself or the browser, see
that document's own limitations section); a **SIEM-ready security event pipeline** - structured
JSON security events (authentication/authorization/rate-limit/prompt-injection/PII/audit) shipped
via the same OpenTelemetry Collector to a pluggable sink (`docs/security/SIEM.md`) - **not** a SIEM
product itself; no Splunk/Elastic Security/Sentinel is connected, an explicit, documented boundary
(the collector-to-real-SIEM hop is a config-only change away, requiring no application code).

**Explicitly out of scope for this PoC** (not implemented, not simulated as implemented):
managed/HA PostgreSQL and Kafka, backup/disaster recovery, model risk management, third-party
model monitoring, formal legal/compliance/DPO review of the AI Act self-assessment
(`docs/governance/AI_ACT.md`), independent security review or penetration testing, data retention
policy, a formal incident response process, i18n/dark mode, browser/frontend-side tracing spans, a
transactional outbox for document-ingestion events (a document uploaded while Kafka is down is
left permanently in `UPLOADED` - reproduced and documented in `docs/resilience/RESILIENCE.md`),
and cross-browser/visual/accessibility E2E coverage (the Playwright suite is real but
Chromium-only, see `docs/testing/E2E_PLAYWRIGHT.md`). Every fake/heuristic component
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

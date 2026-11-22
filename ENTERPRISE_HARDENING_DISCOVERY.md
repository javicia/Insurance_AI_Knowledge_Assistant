# Enterprise Hardening Discovery (FASE 16)

**Date**: 2026-08-11
**Purpose**: baseline inspection of the real repository state before starting the 56-phase
enterprise hardening program, per the governing prompt's explicit instruction to inspect
everything before modifying code. Written from direct inspection (git, filesystem, `pom.xml`,
`application.yaml`, package structure, Docker) in this session, not from memory of prior phases.

## 1. Git state

`git status --short` is clean (no staged/unstaged changes) - the previous session's FASE 15 work
(Docker artifacts, `ADR-013`, frontend docs, the `HttpRequestMethodNotSupportedException` fix) was
left uncommitted per instruction and has apparently since been committed or is otherwise reconciled
by the time this phase starts (`git log` top commit: `fc5439e "add front documentation"`). No stray
temp files, no `target/` tracked, no credentials tracked.

## 2. Current architecture (verified, not assumed)

- **Backend**: single Maven module, hexagonal layers confirmed by directory structure:
  `domain/{aisystem,audit,document,evaluation,governance,model,prompt,rag,security,shared}`,
  `ports/{inbound,outbound}`, `application/{aisystem,audit,configuration,document,evaluation,
  governance,rag,security,shared}`, `adapters/{inbound,outbound,shared}`,
  `infrastructure/{configuration,observability,security}`. `ArchitectureTest` (ArchUnit) enforces
  the boundary mechanically.
- **Backend test count**: 64 test files, 277 `@Test`-annotated methods found by direct grep (the
  286-test figure reported at the close of FASE 15 includes parameterized/dynamic tests that
  don't literally carry the `@Test` annotation once expanded by JUnit at runtime - both numbers are
  consistent with the same suite).
- **Frontend**: `frontend/src/app/{core,shared,layout,features}` - Angular 22 standalone, 47 Vitest
  tests (FASE 15).
- **Single-container packaging**: `Dockerfile` (root) builds Angular first, copies
  `dist/frontend/browser/` into `src/main/resources/static/`, then builds the Spring Boot jar -
  **this is exactly what FASE 16 section 3/47 requires be dismantled**: today, backend and
  frontend are one build, one image, one artifact.
- **No Spring Security dependency exists** (`pom.xml` grep confirms zero `security`/`oauth`
  matches beyond the project's own description string). The `security:` key in `application.yaml`
  is the existing `insurance-ai.security.*` namespace (prompt-injection/PII guardrail toggles from
  FASE 8) - unrelated to authentication/authorization. **There is currently no authentication or
  authorization on any endpoint** - confirmed by `README.md`'s own Production Gap Analysis and by
  the absence of any Spring Security artifact.
- **Evaluation dataset**: `InsuranceEvaluationDataset` - 7 cases (`NAME =
  "insurance-knowledge-baseline-v1"`), confirmed by direct read. FASE 32's target (>=100 cases) is
  a genuine ~14x expansion, not a rounding exercise.
- **`insurance-ai.rag.lexical.min-rank`**: still `0.0` (the FASE 14 remediation's placeholder
  default, never calibrated against a real corpus - confirmed unchanged in `application.yaml`).
- **PII detection**: `RuleBasedPiiGuard`, 4 regex patterns (EMAIL/PHONE/IBAN/NATIONAL_ID, Spain
  only), no port abstraction separating a "production" implementation from the rule-based one -
  `PiiGuardPort` exists but only one implementation is registered (confirmed FASE 15's own audit,
  `FINAL_FRONTEND_AUDIT.md` section 16).
- **Observability**: Micrometer + Actuator (FASE 11) - two custom timers
  (`rag.retrieval.latency`, `rag.llm.latency`), MDC-based `traceId` correlation, no OpenTelemetry,
  no distributed tracing across process boundaries (there is only one process today), no
  Prometheus/Grafana/Loki/Tempo.
- **Infrastructure**: `docker-compose.yml` - single-node PostgreSQL+pgvector, single-broker Kafka
  KRaft, Kafka UI, and (since FASE 15) the combined `insurance-ai` app container. No replication,
  no HA, no backup automation, no Keycloak, no gateway, no WAF.

## 3. Docker resource envelope (governs HA/observability feasibility judgments in later phases)

`docker info`: **12 CPUs, ~15.5 GiB RAM** allocated to the Docker Desktop VM on this machine, ~1.7
TB free disk. This is a genuinely capable local environment - large enough to run a real
multi-node PostgreSQL replica set, a real 3-broker Kafka KRaft cluster, Keycloak, a gateway, a WAF,
and a lightweight OpenTelemetry/Prometheus/Grafana/Loki/Tempo stack concurrently, though multiple
such stacks running simultaneously alongside this machine's numerous **unrelated** already-running
containers (`db-bps-dev`, `ibmmq`, and others observed in earlier sessions) will contend for the
same CPU/RAM/port budget - the same resource-contention pattern already root-caused once in this
project's history (`docs/testing/TESTCONTAINERS.md` section 6, and the FASE 14/15 stale-container
incidents). This is noted here explicitly so that any HA/observability phase that experiences
contention is diagnosed against this baseline, not blamed on the architecture.

**Governing judgment for this program**: real multi-node HA (PostgreSQL streaming replication +
Patroni, 3-broker Kafka with real `kill`-and-recover tests) is resource-feasible here and will be
built and genuinely tested, not merely documented. What is **not** feasible to genuinely produce in
this environment - and will be marked `ENVIRONMENT_LIMITATION` rather than fabricated - is
anything requiring infrastructure or authority outside this single local Docker daemon: a
third-party professional penetration test, a commercial/certified SIEM platform, legal
certification of AI Act compliance, or genuine internet-facing WAF traffic at production scale.

## 4. Documentation already in place (not to be duplicated, only extended)

`README.md`, `FINAL_PROJECT_REPORT.md`, `FINAL_ARCHITECTURE_AUDIT.md` (FASE 14),
`FINAL_FRONTEND_AUDIT.md` (FASE 15), 13 ADRs (`ADR-001`-`ADR-013`), and ~34 topic docs under
`docs/{adr,architecture,audit,demo,evaluation,frontend,governance,observability,rag,resilience,
security,testing}`. This program continues that numbering (`ADR-014` onward, never reusing an
existing number) and that per-topic file structure (new topics get their own `docs/<topic>/` file,
per the pattern README.md itself already documents as deliberate).

## 5. Scope calibration for this program

The governing prompt specifies 56 phases spanning IAM, API Gateway, WAF, rate limiting,
distributed tracing, a full observability stack, security-event logging, PostgreSQL HA, Kafka HA,
backup/DR with a real restore, production-grade PII, AI Act legal review, model risk management, a
100+/200+ case evaluation dataset, RAG evaluation metric expansion, lexical-threshold calibration,
Playwright E2E (20 scenarios), frontend/API security hardening, supply-chain scanning, container
hardening, network segmentation, secret management, database/Kafka security, incident response,
STRIDE threat modeling, automated security assessment, and a final enterprise audit - realistically
the scope of a multi-month platform/security engineering initiative for a small team, compressed
into one autonomous execution. Per the prompt's own section 53 ("if a decision cannot be resolved
technically without a business/legal call, document `DECISION_REQUIRED`/`LEGAL_REVIEW_REQUIRED`
and continue"), this discovery adopts the same posture for scope, not just for individual
decisions: every phase will produce real, working, tested artifacts where genuinely achievable in
this single local environment, and will explicitly mark `ENVIRONMENT_LIMITATION` where it is not -
never a fabricated "PASS".

## 6. Immediate next step

FASE 16's own remaining work item (full frontend/backend/gateway separation into independent
deployables, no shared build/artifact) starts immediately after this document, per the governing
prompt's explicit instruction not to pause between the discovery write-up and the first
implementation phase.

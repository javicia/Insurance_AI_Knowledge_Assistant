# Observability (FASE 11)

Status: living document. See `docs/resilience/RESILIENCE.md` for the failure-handling half of
FASE 11, `docs/audit/AI_AUDIT.md`/`docs/evaluation/AI_EVALUATION.md` for the domain-level
observability this complements (what happened on one request / is quality regressing).

## 1. What already existed before FASE 11

Structured, correlated logging was already in place from earlier phases, not new here:

- `logback-spring.xml` - console pattern includes `[traceId=%X{traceId:-none}]`.
- `TraceIdFilter` (`infrastructure.observability`) - a `@Order(HIGHEST_PRECEDENCE)` servlet filter
  that puts `TraceId` into SLF4J's MDC for the whole request (reusing an inbound `X-Trace-Id`
  header or generating one, echoing it on the response, removing it in `finally`).
- `GlobalExceptionHandler` reads the same MDC value into every `ErrorResponse.traceId`.
- No secrets, full PII, or stack traces are ever returned to a client - `ErrorResponse` carries
  only `code`/`message`/`traceId`; exceptions are logged server-side only.

FASE 11 adds the piece that was missing: a way to ask "how is the system doing right now" beyond
reading log lines - health checks and latency metrics.

## 2. Health checks

`spring-boot-starter-actuator` added (brief section 61: the lightest standard option, not a new
monitoring stack). `GET /actuator/health` auto-aggregates a `DataSource` health indicator (the
already-existing `DataSource` bean) and a Kafka health indicator (the already-existing
`KafkaAdmin` bean) with no new code - both were already required beans for this application to run
at all, so this is genuinely free.

Exposure is intentionally narrow (`management.endpoints.web.exposure.include:
health,info,metrics`, `application.yaml`) - never `"*"`, consistent with this PoC's "no IAM
introduced" stance (`docs/governance/AI_GOVERNANCE.md` section 7): `env`/`beans`/`heapdump`/
`shutdown` are never exposed. `management.endpoint.health.show-details: never` is Boot's own
default, kept explicit so a future edit cannot silently loosen it to leak DB/Kafka connection
details in an unauthenticated response.

## 3. Latency metrics

Two custom Micrometer timers, both visible via `GET /actuator/metrics/{name}`:

- `rag.retrieval.latency` (tag: `outcome` = `HYBRID`/`SEMANTIC_ONLY`/`LEXICAL_ONLY`/`ERROR`) -
  `HybridRetrievalService.retrieve`, the whole pipeline (query expansion through context
  selection) as one span, not per-stage. Per-stage *counts* already exist as `RetrievalDiagnostics`
  and are recorded per-request in AI Audit (FASE 9) - this metric answers "how long did retrieval
  take", a different question from "how many candidates survived each stage."
- `rag.llm.latency` (tags: `provider`, `outcome` = `SUCCESS`/`ERROR`) - `AskInsuranceKnowledgeUseCase`,
  around the `LlmProvider#complete` call specifically (isolated from retrieval and guardrail time).

Both are recorded via a plain `MeterRegistry` dependency injected into the relevant `application`
service - the same layer that already depends on SLF4J's `Logger`, a comparable third-party
cross-cutting concern; `MeterRegistry` is provided automatically once `spring-boot-starter-actuator`
is on the classpath, no manual bean configuration needed. `domain` remains untouched - no metrics
code there.

AI Audit's per-request `latencyMs` (FASE 9) measures the *whole* `ask()` call (guardrail + retrieval
+ LLM + audit write); these two Micrometer timers break that total down into its two most
expensive parts. They intentionally overlap in what they measure - one is a persisted per-request
fact for forensic lookup, the other is an aggregated, queryable metric for "is this generally
slow", each fit for a different purpose.

## 4. What is deliberately not here

- No Prometheus/Grafana/OpenTelemetry/APM stack - `docs/architecture/ARCHITECTURE.md`'s
  Production Gap Analysis lists distributed tracing/model monitoring as explicitly out of scope
  for this PoC. `/actuator/metrics` is queryable directly (`curl localhost:8080/actuator/metrics/
  rag.llm.latency`) without needing a metrics backend to demonstrate the instrumentation exists
  and works.
- No per-retrieval-stage (semantic vs. lexical vs. reranking) timing - only the pipeline total.
  Would need four more timers for marginal value at this PoC's scale.
- No embedding-generation-specific timer - embedding happens inside the semantic branch of
  `HybridRetrievalService.retrieve`, already covered by `rag.retrieval.latency`.
- No Kafka consumer lag/throughput dashboard - `spring-boot-starter-actuator` auto-instruments
  Kafka client metrics once present, so the raw data is available via `/actuator/metrics`, but no
  dedicated view/alerting was built (brief section 61: no overengineering for a PoC).
- No authentication on `/actuator/**` - same PoC-wide limitation as every other REST surface; the
  exposure allow-list (section 2) is the actual mitigation.

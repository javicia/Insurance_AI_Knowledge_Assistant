# FASE 25 — Distributed Tracing: closure report

**Status: CLOSED.** Every checklist item below is backed by evidence captured against the real
`docker compose` stack (ten services), not against unit tests or a local `spring-boot:run`.

Two raw Jaeger API responses are committed alongside this report as primary evidence:

- `fase25_evidence_unified_trace.json` — a `POST /api/chat` trace (gateway span parenting the
  backend span, which parents the JDBC spans).
- `fase25_evidence_kafka_ingestion_trace.json` — a `POST /api/documents` trace, 26 spans, covering
  the whole asynchronous ingestion pipeline across three Kafka hops.

---

## 1. What was actually built

```
Browser ──► WAF (ModSecurity/OWASP CRS, :8000)
              └─► Gateway (Spring Cloud Gateway, :8082)   ── W3C traceparent ──►
                     └─► Backend (Spring Boot, :8080)
                            ├─► PostgreSQL (JDBC spans)
                            └─► Kafka (producer + consumer spans)

Gateway ─┐
Backend ─┴─ OTLP/HTTP :4318 ─► OTel Collector ─► Jaeger (:16686)
```

Both JVM services use `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter` 2.30.0
(the OpenTelemetry-project-maintained starter), **not** Spring Boot's own
`management.tracing.*` auto-configuration — see section 2.1 for why that was not an option.

Sampling is `always_on`. That is a deliberate PoC choice, not a production default: a real
deployment would use `parentbased_traceidratio` plus always-sample-on-error to bound collector and
storage volume.

## 2. Real incidents found and fixed

Each of these failed **silently** — the application returned HTTP 200, no error was logged, and no
test failed. All were found by querying Jaeger's HTTP API for a real request and comparing what
arrived against what should have.

### 2.1 Spring Boot 4.1.0 ships no working tracing auto-configuration

`management.tracing.*` + `micrometer-tracing-bridge-otel` produced `traceId=none spanId=none` on
every log line. `/actuator/conditions` showed `NoopTracerAutoConfiguration` active. Direct jar
inspection confirmed the cause: `spring-boot-actuator-autoconfigure-4.1.0.jar` contains **zero**
tracing classes (that code moved to a separate `spring-boot-micrometer-tracing` module), and that
replacement module ships four classes, none of which construct a real OpenTelemetry SDK
`Tracer`/propagator bean.

**Fix:** switched both modules to `opentelemetry-spring-boot-starter`.

### 2.2 `NoClassDefFoundError` from two competing JDBC instrumentations

The starter bundles its own JDBC instrumentation; a previously-added
`net.ttddyy:datasource-micrometer-spring-boot` wrapped the same `DataSource`, crashing on the first
query with `NoClassDefFoundError: io/opentelemetry/api/impl/InstrumentationUtil`.

**Fix:** removed `datasource-micrometer-spring-boot`, and imported
`opentelemetry-instrumentation-bom` so every `io.opentelemetry*` artifact resolves to one
mutually-compatible version set.

### 2.3 Trace IDs never reached the logs

Spans exported correctly to Jaeger while log lines still read `traceId=none spanId=none`.
`opentelemetry-logback-mdc-1.0` is a Logback **appender**, not an auto-installing MDC filter — it
must explicitly wrap the real output appender. The MDC keys are also `trace_id`/`span_id`
(underscored, OTel semantic convention), not the camelCase names originally assumed.

**Fix:** in both modules' `logback-spring.xml`, the real console appender was renamed
`CONSOLE_PLAIN` and wrapped by a `CONSOLE` appender of type `OpenTelemetryAppender`.

### 2.4 The gateway and the backend produced two **separate, unrelated traces**

The most serious of the five, and invisible without cross-checking Jaeger: the gateway emitted a
real inbound server span, the backend emitted a real trace of its own, and the two had **different
trace IDs** — so no end-to-end trace existed at all.

Root cause: Spring Cloud Gateway's `NettyRoutingFilter` proxies every request through reactor-netty's
raw `HttpClient`, never through a Spring-managed `WebClient`/`RestClient`, so none of the starter's
bundled client instrumentation applies and **no `traceparent` header was ever sent downstream**.
OpenTelemetry publishes no importable reactor-netty client instrumentation for this non-javaagent
integration style (the raw-Netty modules upstream are javaagent-only bytecode transformers).

**Fix:** `GatewayTracePropagationFilter`, a `GlobalFilter` ordered immediately before
`NettyRoutingFilter`, which injects the W3C trace-context headers itself. Getting it right required
fixing three distinct bugs in the filter, each of which also failed silently:

| # | Bug | Why it produced nothing |
|---|-----|-------------------------|
| 1 | Reading the context in the filter body | That runs at reactive-chain *assembly* time, before the server span is active. Fixed with `Mono.deferContextual`, deferring to subscription time. |
| 2 | `ContextPropagationOperator.getOpenTelemetryContextFromContextView(...)` | Returns an **invalid, empty** span context here — Gateway's filter chain does not carry the OTel context in the Reactor `Context` map that operator reads. `Context.current()` is correct once bug 1 is fixed. |
| 3 | `GlobalOpenTelemetry.getPropagators()` | Silently returns a **no-op** propagator: the starter registers a real `OpenTelemetry` *bean* but does not install it as the JVM-global instance. Symptom: valid span context, `inject()` completing without error, and no header ever written. Fixed by constructor-injecting the bean. |

Regression test: `GatewayTracePropagationFilterTest` (2 tests).

### 2.5 Periodic HTTP 404 noise from unused OTLP signals

The starter also exports logs and metrics over OTLP by default. The collector defines only
`traces` and `logs` pipelines, so metrics export logged `Failed to export metrics ... HTTP 404`
every ~30s, and log export did the same before it was disabled.

**Fix:** `otel.logs.exporter: none` and `otel.metrics.exporter: none` in both modules. This project
ships security events over syslog instead (`docs/security/SIEM.md`); a metrics pipeline is
deliberately out of scope for this PoC, not an oversight.

## 3. Closure checklist — evidence

| # | Item | Status | Evidence |
|---|------|--------|----------|
| 1 | Backend builds | PASS | `./mvnw clean verify` → `BUILD SUCCESS`, `EXIT_CODE=0`, 344/344 tests, 0 failures/errors/skips |
| 2 | Gateway builds | PASS | `./mvnw clean verify` → `BUILD SUCCESS`, `EXIT_CODE=0`, 13/13 tests |
| 3 | New Docker images built | PASS | `docker compose build backend gateway` → `EXIT_CODE=0`; running containers verified against the newly-built image IDs |
| 4 | Collector operative | PASS | `curl http://localhost:13133/health` → HTTP 200 `{"status":"Server available"}` |
| 5 | Jaeger operative | PASS | `docker compose ps` → `insurance-ai-jaeger  Up (healthy)`; its HTTP API answered every query below |
| 6 | Real trace generated | PASS | `POST http://localhost:8000/api/chat` through the WAF with a real Keycloak JWT → HTTP 200 |
| 7 | Trace received by the Collector | PASS | Trace retrievable from Jaeger, which only receives via the collector's OTLP pipeline |
| 8 | Trace visible in Jaeger | PASS | `GET /api/traces/b7f5cb33ff1b0b03ebfb4194dac3dadf` → 10 spans |
| 9 | W3C traceparent propagation | PASS | Backend span is a child of the gateway span across a process boundary — only possible via the header |
| 10 | Gateway span | PASS | `insurance-ai-gateway  POST  a620f43887ccd7e0` (root) |
| 11 | Backend span | PASS | `insurance-ai-backend  POST /api/chat  c20d35fed46cf175` |
| 12 | JDBC spans | PASS | `SELECT insurance_ai.documents`, `SELECT insurance_ai.document_versions`, `SELECT insurance_ai.prompts`, `INSERT insurance_ai.ai_audit_records` |
| 13 | Kafka spans | PASS | See section 4 — producer *and* consumer spans across three topics |
| 14 | LLM span | **ABSENT — not applicable to this run** | The stack runs `INSURANCE_AI_PROVIDER=fake` (no API keys), and `FakeLlmAdapter` performs no outbound HTTP call, so there is no client span to emit. Stated as absent rather than implied present. |
| 15 | Same traceId gateway↔backend | PASS | Both spans carry `b7f5cb33ff1b0b03ebfb4194dac3dadf` |
| 16 | Different spanIds per service | PASS | `a620f43887ccd7e0` (gateway) vs `c20d35fed46cf175` (backend) |
| 17 | correlationId independent | PASS | `X-Trace-Id: e2e-final-1786530349` echoed in the response and present in backend logs as `correlationId=...`, alongside — and distinct from — `trace_id`/`span_id` |
| 18 | Observability fail-safe | PASS | See section 5 |
| 19 | Real WAF→Gateway→Backend E2E | PASS | Every request above entered through the WAF on `:8000`; none addressed the gateway or backend directly |

## 4. Kafka + full ingestion pipeline in one trace

`POST /api/documents` (a real PDF, through the WAF) produced trace
`19985489325d2f30b95178e1099dcd25` — **26 spans**, spanning three asynchronous Kafka hops, with
trace context carried through Kafka message headers:

```
insurance-ai-gateway  POST                                    610ba773bcadf8d5  (root)
└─ insurance-ai-backend  POST /api/documents                  fa6397bb7321bbe5
   ├─ INSERT insurance_ai.documents / document_versions / document_version_contents
   └─ insurance.document.uploaded publish                     c14be17ca580abc4   [producer]
      └─ insurance.document.uploaded process                  fbaaf17a1ef047d6   [consumer]
         ├─ SELECT/DELETE/INSERT insurance_ai.document_chunks
         └─ insurance.document.processed publish              9c3393da0449e80b   [producer]
            └─ insurance.document.processed process           51681bfda40b6c06   [consumer]
               ├─ INSERT public.vector_store                  bf7376dee6fe8d89   [embeddings]
               └─ insurance.document.embedded publish         99ef1fe0d2e38f37   [producer]
```

This is the strongest single piece of evidence in FASE 25: a fully asynchronous, multi-hop
pipeline reconstructed as one causally-ordered trace.

## 5. Observability fail-safe

The requirement is that losing the observability stack must never degrade the product.

| Step | Action | Result |
|------|--------|--------|
| 1 | `docker compose stop otel-collector` | Collector down |
| 2 | Real `POST /api/chat` through the WAF | **HTTP 200**, normal answer body |
| 3 | `docker compose stop jaeger` | Both collector and Jaeger down |
| 4 | Real `POST /api/chat` through the WAF | **HTTP 200**, `GROUNDED` answer with 4 citations |
| 5 | `GET /actuator/health` on backend and gateway | Both `{"status":"UP"}`, HTTP 200 |
| 6 | `docker compose start jaeger otel-collector` | Restored; collector health endpoint HTTP 200 |
| 7 | Real `POST /api/chat` | HTTP 200, and trace `33c2df26f98d86c25d6b5505712a6cb9` (12 spans) retrievable from Jaeger again |

Tracing degrades to a no-op; the business path is unaffected in either direction.

## 6. Two identifiers, deliberately not merged

| | `X-Trace-Id` / MDC `correlationId` | W3C `traceparent` / MDC `trace_id`+`span_id` |
|---|---|---|
| Owner | This application | OpenTelemetry SDK |
| Audience | API callers, support, the AI Audit log | Engineers, in Jaeger |
| Lifetime | Stable, business-facing, returned in responses | Per-request technical trace |
| Format | Opaque string (client-suppliable) | W3C hex trace/span IDs |

They coexist on the same log line, e.g.:

```
[traceId=b7f5cb33ff1b0b03ebfb4194dac3dadf spanId=c20d35fed46cf175 correlationId=e2e-final-1786530349]
```

The audit record is keyed by `correlationId`, so an auditor can find a decision without depending
on a sampled telemetry backend being retained. A previous revision of `TraceId` used the MDC key
`traceId` for the correlation ID, which collided with OpenTelemetry's own key; it now uses
`correlationId`.

## 7. Honest limitations

- **Sampling is `always_on`** — a PoC choice; unsuitable for production volume.
- **No LLM span was observed** because the deployment runs the fake provider (item 14). With a real
  provider the outbound HTTP call is instrumented by the starter's HTTP client instrumentation, but
  that is **not** demonstrated here and is not claimed.
- **No metrics pipeline.** Traces and security-event logs only.
- **The gateway's propagation is a hand-written filter**, not vendor instrumentation. It is covered
  by unit tests and by the end-to-end evidence above, but it is application code that must be kept
  in step with future Spring Cloud Gateway changes.
- **`otel-collector` has no Docker healthcheck** (distroless image, no shell). Its own
  `health_check` extension on `:13133` is the real check, verified externally.

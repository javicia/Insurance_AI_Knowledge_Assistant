# Distributed Tracing (FASE 25)

Status: living document. Closes the gap `docs/security/SIEM.md` and prior audit reports
explicitly flagged as `NOT IMPLEMENTED`: real OpenTelemetry, not just the `X-Trace-Id` header
correlation ID that predates this phase. Verified with real evidence (section 7), not assumed.

## 1. What changed, precisely

Before this phase: a single opaque `X-Trace-Id` header + SLF4J MDC value, generated at the WAF/
gateway edge and reused end-to-end - genuinely useful for grepping "every log line for one
request", but **not** distributed tracing: no spans, no parent/child relationships, no per-hop
timing, no W3C `traceparent` propagation, no trace visualization.

After this phase: real OpenTelemetry spans, exported via OTLP, for every hop:

```
Browser
   |
   v (no span - the WAF is not instrumented; see section 6)
WAF
   |
   v (gateway span starts here)
Gateway (Spring Cloud Gateway, auto-instrumented HTTP client + server spans)
   |  W3C traceparent header propagated automatically
   v
Backend (HTTP server span, JDBC spans, Kafka producer/consumer spans, LLM client spans)
   |
   +--> PostgreSQL (JDBC client span, via the OTel starter's own JDBC instrumentation)
   +--> Kafka (producer span on publish, consumer span on @KafkaListener)
   +--> LLM provider (HTTP client span, via Spring AI's auto-instrumented RestClient/WebClient)
   |
   v
OTel Collector (OTLP receiver -> batch processor -> OTLP exporter)
   |
   v
Jaeger (all-in-one, local trace storage + UI - http://localhost:16686)
```

## 2. Real incident: Spring Boot 4.1.0's own tracing auto-configuration produces no `Tracer` bean

This is the central finding of this phase, discovered by evidence, not assumed away.

**Initial approach (failed)**: `io.micrometer:micrometer-tracing-bridge-otel` +
`io.opentelemetry:opentelemetry-exporter-otlp` + Spring Boot's own `management.tracing.*`/
`management.otlp.tracing.*` properties - the combination that works on Spring Boot 3.x. The
application started without error, every request succeeded, but every log line showed
`traceId=none spanId=none`, and Jaeger showed **zero** received spans.

**Root-caused via `/actuator/conditions`** (with `management.endpoints.web.exposure.include`
temporarily widened to include `conditions` for the investigation): `NoopTracerAutoConfiguration`
had activated (a real, do-nothing fallback `Tracer` bean), and
`MicrometerTracingAutoConfiguration#propagatingReceiverTracingObservationHandler`/
`#propagatingSenderTracingObservationHandler` both stayed unmatched -
`"@ConditionalOnBean (types: io.micrometer.tracing.propagation.Propagator) did not find any
beans of type io.micrometer.tracing.propagation.Propagator"`. Nothing in the classpath actually
constructed the real OpenTelemetry SDK (`OpenTelemetrySdk`/`SdkTracerProvider`) or a
`Propagator` bean.

**Confirmed by inspecting the actual jars**: `spring-boot-actuator-autoconfigure-4.1.0.jar`
contains **zero** tracing-related classes (a real, verified fact - `unzip -l ... | grep -i
tracing` returns nothing). Spring Boot 4 moved tracing support to a new, separate module,
`org.springframework.boot:spring-boot-micrometer-tracing`, but that module's own
`AutoConfiguration.imports` lists exactly four classes
(`MicrometerTracingAutoConfiguration`, `NoopTracerAutoConfiguration`,
`OtlpExemplarsAutoConfiguration`, `PrometheusExemplarsAutoConfiguration`) - **none of which
construct a real OpenTelemetry SDK `Tracer`/`Propagator` bean**. As of this Spring Boot version,
that construction logic simply does not exist in Spring Boot's own modules (this may change in a
later Boot 4.x point release - re-check before assuming this is permanent).

**Fix**: switched to `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`
(version `2.30.0`) - the OpenTelemetry-project-maintained starter, which ships its own complete
SDK auto-configuration independent of Spring Boot's release cadence. Confirmed via the same
`/actuator/conditions` inspection: `OpenTelemetryAutoConfiguration.OpenTelemetrySdkConfig` now
matches positively, and Jaeger's `/api/services` endpoint lists `insurance-ai-backend` as a real
registered service with real spans (section 7).

## 3. Real incident: JDBC instrumentation conflict

A second dependency, `net.ttddyy:datasource-micrometer-spring-boot`, was added first (before the
incident in section 2 was fully understood) specifically to get JDBC spans. Once the OTel starter
was added, the two libraries' JDBC instrumentation collided:
`NoClassDefFoundError: io/opentelemetry/api/impl/InstrumentationUtil`, crashing on the very first
JDBC query. Root-caused via the real stack trace (`io.opentelemetry.instrumentation.jdbc.internal
.OpenTelemetryStatement.wrapCall`), not guessed. **Fixed by removing
`datasource-micrometer-spring-boot` entirely** - the OTel starter's own bundled JDBC
instrumentation (`io.opentelemetry.instrumentation:opentelemetry-jdbc`, pulled in transitively)
already covers this need, and running two JDBC-wrapping libraries simultaneously against the same
`DataSource` was never a supported combination.

A related fix, applied alongside: the starter's transitive `io.opentelemetry:opentelemetry-api`
resolution needed to be pinned consistently across every `io.opentelemetry*` artifact via
`io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom` (imported in
`dependencyManagement`) - without it, Maven's normal "nearest wins" resolution could pull a
mismatched `opentelemetry-api` version relative to what the starter's own JDBC/HTTP
instrumentation modules expect.

## 4. Real incident: MDC correlation requires an explicit Logback appender

Even after spans were confirmed reaching Jaeger correctly (section 2's fix), every log line
*still* showed `traceId=none spanId=none` - a real, separate gap, not the same bug re-appearing.

**Root cause**: `io.opentelemetry.instrumentation:opentelemetry-logback-mdc-1.0` is a Logback
**appender** (`io.opentelemetry.instrumentation.logback.mdc.v1_0.OpenTelemetryAppender`), not a
global MDC auto-populator the way Micrometer Tracing's own bridge is. It must be explicitly wired
into `logback-spring.xml`, wrapping whatever appender actually writes the log line - Spring
Boot's `OpenTelemetryAppenderAutoConfiguration` only registers the underlying `install(...)` call
automatically; it does not modify an application's own `logback-spring.xml` for it.

**Fixed** in both `backend/src/main/resources/logback-spring.xml` and
`gateway/src/main/resources/logback-spring.xml`: renamed the original `CONSOLE` appender to
`CONSOLE_PLAIN`, and added a new `CONSOLE` appender of class `OpenTelemetryAppender` wrapping it.
**Also discovered while fixing this**: the correct MDC key names are `trace_id`/`span_id`
(underscored, OpenTelemetry's own semantic-convention naming) - the project's original log
pattern guessed `traceId`/`spanId` (camelCase), which silently never matched anything. Confirmed
correct via real log output showing a populated 32-hex-character `trace_id` and 16-hex-character
`span_id` together with the pre-existing `correlationId` on the same line (section 7).

## 5. Two different IDs, deliberately never merged into one

A related, separate real bug, found and fixed in this same phase: the pre-existing
`TraceId.MDC_KEY` constant (`domain.shared.TraceId`, the `X-Trace-Id` business correlation
header) was literally the string `"traceId"` - colliding in principle with the OTel MDC key this
phase introduces (`trace_id`, underscored - not actually the same string, but close enough to
cause real confusion for anyone reading logs or grepping for `traceId=`). **Fixed by renaming**
`TraceId.MDC_KEY`'s value to `"correlationId"` (the Java constant name and every call site
referencing it - `SecurityErrorHandler`, `GlobalExceptionHandler`, `AuditController`,
`AskInsuranceKnowledgeUseCase`, every `SecurityEvent`'s `trace.id` field - are unchanged, since
they all go through the constant, not a hardcoded string).

**These remain two genuinely different concepts, not accidentally duplicated data**:
`correlationId` (`X-Trace-Id`) is a single opaque, human-chosen-format ID that survives the
*whole* business operation as one flat string, useful for the AI Audit trail and security events
(which predate and do not depend on OpenTelemetry) and for correlating with tooling that only
understands a simple header, not W3C trace context. `trace_id`/`span_id` are the real,
hierarchical, per-hop OpenTelemetry identifiers, useful for visualizing exactly which hop (gateway
routing? backend JDBC query? the LLM call itself?) took how long for one specific request.

## 6. What is and is not instrumented

| Hop | Instrumented? | Mechanism |
|---|---|---|
| Gateway HTTP routing | Yes | `opentelemetry-spring-boot-starter`'s Spring Cloud Gateway instrumentation |
| Backend HTTP request handling | Yes | The starter's Spring MVC (Servlet) instrumentation |
| Backend -> PostgreSQL (JDBC) | Yes | The starter's own bundled JDBC instrumentation (section 3) |
| Backend -> Kafka (producer) | Yes | `spring.kafka.template.observation-enabled: true` + the starter's Kafka instrumentation |
| Backend -> Kafka (consumer, `@KafkaListener`) | Yes | `spring.kafka.listener.observation-enabled: true` |
| Backend -> LLM provider (OpenAI/Anthropic) | Yes | The starter's HTTP client instrumentation (Spring AI's `RestClient`/`WebClient`) |
| Log-to-trace correlation (`trace_id`/`span_id` in log lines) | Yes | `opentelemetry-logback-mdc-1.0`, explicitly wired (section 4) |
| **WAF (ModSecurity/nginx)** | **No** | nginx has no OpenTelemetry integration built in and none was added - see below |
| **Frontend (Angular, browser)** | **No** | No browser-side OpenTelemetry SDK was added - out of scope for this phase |

**The WAF is a genuine, honestly-documented gap in span coverage, not an oversight**: it forwards
every request header unmodified (verified: no `proxy_hide_header`/header-stripping directive in
`waf/conf/default.conf.template` touches `traceparent`), so trace propagation through it works
correctly once the gateway starts the first real span - but the WAF hop itself contributes no
span of its own, and any latency spent inside ModSecurity's rule evaluation is invisible in the
trace. Adding real WAF-hop tracing would require either an OpenTelemetry nginx module (not
present in the `owasp/modsecurity-crs` base image) or a sidecar - not implemented here,
documented as a limitation.

## 7. Verification performed (real evidence, not "Jaeger is healthy")

A real `GET /api/governance/ai-systems` request, authenticated with a real Keycloak-issued JWT,
followed by:

1. **Jaeger's own API** (`GET http://localhost:16686/api/traces?service=insurance-ai-backend`)
   returned a trace containing exactly two spans sharing one `traceID`
   (`50c70122daea37ed9ba37f0bc43fb8d4`):
   - `GET /api/governance/ai-systems` (spanID `24f79c0d4a2cb6d5`), duration 1,521,664µs,
     `references: []` (the root span).
   - `SELECT insurance_ai.ai_systems` (spanID `8b27aa4a1ae9aaf7`), duration 2,091µs,
     `references: [{"refType": "CHILD_OF", "spanID": "24f79c0d4a2cb6d5"}]` - an explicit,
     machine-verified parent/child relationship, not inferred.
2. **Real log output**, same request: `[traceId=07fb2e8f6674c25cc05e3031db84f9f6
   spanId=ceb2d5b2ebb64eb9 correlationId=<uuid>]` - confirming the log-to-trace correlation fix
   (section 4) works, and that `correlationId` remains a distinct value from `traceId` (section 5).
3. `/actuator/conditions` confirmed `OpenTelemetryAutoConfiguration.OpenTelemetrySdkConfig` and
   `OpenTelemetryAppenderAutoConfiguration.LogbackAppenderConfig` both positively matched.

See `FASE_25_REPORT.md` for the full command-by-command record, including the Docker-deployed
(not just local `spring-boot:run`) re-verification.

## 8. Sampling

`otel.traces.sampler: always_on` in both `application.yaml`s - every request is sampled and
exported. A deliberate PoC choice for demonstrability (any single manually-triggered request is
guaranteed to show up in Jaeger), explicitly **not** a production default - a real deployment
would use `parentbased_traceidratio` with a fraction (e.g. `0.1`) plus an always-sample-on-error
policy, to bound collector/storage volume.

## 9. Known limitations (explicit)

- No frontend/browser-side spans (section 6).
- No WAF-hop span (section 6).
- Jaeger's all-in-one image stores traces in memory only - a container restart loses trace
  history. A real deployment would point it (or its OTLP-compatible replacement) at persistent
  storage (Elasticsearch/Cassandra) - out of scope for this PoC.
- 100% sampling (section 8) is not a production-appropriate default.
- No trace-based alerting/SLO tooling - this closes the *instrumentation* gap, not a full APM
  product.
- The OTel starter's own OTLP *log*-export feature (a separate, unused capability from the
  Logback MDC correlation in section 4, and from this project's own syslog-based security-event
  pipeline, `docs/security/SIEM.md`) initially produced periodic `HTTP 404` warnings against a
  collector `/v1/logs` receiver that was never configured - explicitly disabled
  (`otel.logs.exporter: none` in both `application.yaml`s) rather than left noisy.

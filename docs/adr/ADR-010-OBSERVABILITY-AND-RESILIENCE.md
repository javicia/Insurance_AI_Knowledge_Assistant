# ADR-010: Observability and Resilience Hardening

## Status

Accepted — FASE 11.

## Context

FASE 11 needs to close two related gaps identified against brief section 55: no way to observe
system health/latency beyond log lines, and the LLM call path had no explicit timeout/retry/backoff
and blanket-classified every failure as transient regardless of whether it was actually retry-safe.
Structured logging, trace-id correlation, and Kafka's retry/DLT were already solid (earlier phases)
and are explicitly out of scope for rework here.

## Decisions

**1. `spring-boot-starter-actuator`, not a custom health/metrics stack.** The single new
dependency this phase adds. `GET /actuator/health` gets DB and Kafka health checks for free from
beans that already exist; `MeterRegistry` becomes available for the two custom latency timers with
no additional wiring. Rejected alternative: hand-rolling a `/health` endpoint and manual latency
logging - strictly more code to achieve what a standard, minimal Spring Boot module already
provides.

**2. No Prometheus/Grafana/OpenTelemetry.** `/actuator/metrics/{name}` is directly queryable and
proves the instrumentation is real without a metrics backend. Brief section 61 (no overengineering)
and the Production Gap Analysis already list full observability/APM infrastructure as out of scope
for this PoC - adding a scrape target and dashboard would be exactly that overengineering.

**3. Two timers, not per-stage instrumentation.** `rag.retrieval.latency` (whole
`HybridRetrievalService.retrieve` pipeline) and `rag.llm.latency` (the `LlmProvider#complete` call
specifically). Per-stage (semantic/lexical/fusion/reranking) *counts* already exist via
`RetrievalDiagnostics` and AI Audit (FASE 9); adding four more timers for per-stage *latency* was
judged unnecessary detail at this PoC's scale - two timers already answer "is retrieval slow" and
"is the LLM call slow" independently, which is what brief section 55 actually asks for.

**4. Reuse Spring AI's own auto-configured retry (`spring-ai-autoconfigure-retry`), do not add
resilience4j/spring-retry.** Already a transitive dependency of the OpenAI/Anthropic starters,
already wraps every `ChatModel#call`. Its own defaults (10 attempts, backoff up to 3 minutes) are
tuned for a background caller, not a synchronous `POST /api/chat` request, so FASE 11 makes
`spring.ai.retry.*` explicit and appropriate (`max-attempts: 3`, backoff capped at 3s) rather than
leaving unreviewed defaults or introducing a second, parallel retry mechanism a new dependency
would require.

**5. HTTP timeouts via `spring.http.clients.*`, not a custom `RestClient` bean.** Spring Boot 4's
own properties apply to every auto-configured `RestClient.Builder`/`WebClient.Builder`, including
Spring AI's. Simpler than constructing and wiring a dedicated `RestClient` bean just to set two
`Duration` values.

**6. LLM adapters classify `HttpClientErrorException` (4xx) as `PermanentProcessingException`,
everything else stays `TransientProcessingException`.** Both are standard types already on the
classpath (`org.springframework.web.client`, and the domain's own FASE 4 exception hierarchy) - no
new exception type, no new dependency. This directly matches Spring AI's own retry policy default
(`on-client-errors: false`): a 4xx was never going to be retried internally either, so classifying
it as permanent here is consistent with, not duplicating, that behaviour.

**7. `GlobalExceptionHandler` maps `PermanentProcessingException` to 502, distinct from
`TransientProcessingException`'s 503.** Before this phase both mapped identically to 503. A
provider outright rejecting a request (bad credentials, malformed payload) is a different failure
mode from "temporarily unavailable, try again" - 502 Bad Gateway communicates that distinction to
API consumers instead of implying a retry will help when it will not.

**8. No circuit breaker, no automatic provider failover.** Both would be real resilience
improvements at production scale, but this PoC is a single instance talking to one statically
configured provider (`insurance-ai.ai.provider`) - Spring AI's bounded retry (decision 4) is
judged sufficient here; a circuit breaker protects against sustained failure under load this PoC
does not generate. Documented as a known limitation, not implemented.

## Consequences

- `HybridRetrievalService` and `AskInsuranceKnowledgeUseCase` both gained a `MeterRegistry`
  constructor dependency - the same layer that already depends on SLF4J's `Logger`, a comparable
  cross-cutting concern; `domain` remains completely untouched by this phase.
- `PermanentProcessingException`'s Javadoc was broadened from document-processing-specific wording
  to describe both its uses (document processing FAILED marking, and now LLM 4xx rejection) - the
  class itself did not change, only its documented scope.
- `docs/observability/OBSERVABILITY.md` and `docs/resilience/RESILIENCE.md` are the canonical
  references for this phase; this ADR records why, those documents record what.
- Existing `OpenAiLlmAdapterTest`/`AnthropicLlmAdapterTest`/`GlobalExceptionHandlerTest` gained one
  new case each (4xx → permanent, 502 mapping) alongside their unchanged prior cases - a strict
  addition, not a rewrite of prior behaviour.

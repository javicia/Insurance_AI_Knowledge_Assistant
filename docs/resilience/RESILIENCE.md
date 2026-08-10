# Resilience (FASE 11)

Status: living document. See `docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md` for the
transient/permanent exception hierarchy this phase extends to the LLM call path, and
`docs/observability/OBSERVABILITY.md` for the companion metrics/health-check half of FASE 11.

## 1. What already existed before FASE 11

- **Exception hierarchy** (`domain.shared.exception`): `DomainException`/`ApplicationException`/
  `InfrastructureException`, the last split into `TransientProcessingException` (retry-safe) and
  `PermanentProcessingException` (not retry-safe) - see ADR-004.
- **Kafka**: `KafkaErrorHandlingConfiguration` already configures a `DefaultErrorHandler` with
  `DeadLetterPublishingRecoverer` and `ExponentialBackOff(1_000L, 2.0, max 10_000L)`, excluding
  `PermanentProcessingException` from retries. This was already solid and is unchanged by FASE 11.

What was missing: the LLM call path (`OpenAiLlmAdapter`/`AnthropicLlmAdapter`) blanket-wrapped
*every* failure - a 4xx auth error identically to a 5xx timeout - as `TransientProcessingException`,
and had no explicit timeout or retry/backoff configuration at all.

## 2. HTTP timeouts

`spring.http.clients.connect-timeout: 5s` / `read-timeout: 30s` (`application.yaml`) - Spring Boot
4's own `spring.http.clients.*` properties, which the auto-configured `RestClient.Builder`/
`WebClient.Builder` beans (used by Spring AI's OpenAI/Anthropic HTTP clients, among everything
else) pick up automatically. No custom `RestClient` bean, no new dependency - these properties
bound every auto-configured HTTP client in the application, not just the LLM ones.

## 3. Retry and backoff

Spring AI auto-configures its own `RetryTemplate` around every `ChatModel#call`
(`spring-ai-autoconfigure-retry`, already a transitive dependency of the OpenAI/Anthropic
starters - no new dependency added). Its defaults are tuned for a batch/background caller (10
attempts, exponential backoff up to 3 minutes between attempts) - inappropriate for a synchronous
`POST /api/chat` request, where a caller should get a definitive answer, or a clear failure, in a
few seconds. FASE 11 tunes this down explicitly in `application.yaml`:

```yaml
spring.ai.retry:
  max-attempts: 3
  backoff:
    initial-interval: 500ms
    multiplier: 2
    max-interval: 3s
```

`on-client-errors` is left at Spring AI's own default (`false`): a 4xx response is not retried by
Spring AI's own RetryTemplate - by the time it reaches our adapter code, either the request
genuinely failed as a permanent client error, or a transient failure was already retried
internally. This means `resilience4j`/`spring-retry` were **not** added as new dependencies -
Spring AI already ships this capability; FASE 11 only makes its configuration explicit and
appropriate for this synchronous endpoint, rather than leaving unreviewed defaults in place (brief
section 61: reuse what the framework already provides before reaching for a new dependency).

## 4. Failure classification at the LLM adapter boundary

`OpenAiLlmAdapter`/`AnthropicLlmAdapter` now distinguish, using the standard Spring Web exception
hierarchy (`org.springframework.web.client.HttpClientErrorException`, no new dependency):

- **`HttpClientErrorException`** (4xx - invalid API key, malformed request, rate limit exhausted)
  → `PermanentProcessingException` (`..._CHAT_COMPLETION_REJECTED`). Already excluded from Spring
  AI's own retry by `on-client-errors: false`, and correctly not retry-safe: nothing about waiting
  and trying again fixes an invalid key.
- **Everything else** (5xx, timeout, connection drop, or any other `RuntimeException`) →
  `TransientProcessingException` (`..._CHAT_COMPLETION_FAILED`), unchanged from before FASE 11.

`GlobalExceptionHandler` now maps `PermanentProcessingException` to `502 Bad Gateway` (the
upstream provider rejected the request outright) rather than `TransientProcessingException`'s
`503 Service Unavailable` (retrying later might help) - a real semantic distinction, not just an
internal classification with no externally visible effect.

## 5. What this does and does not achieve for the synchronous chat path

`POST /api/chat` is synchronous - Spring AI's internal retry already runs *inside* the single
`llmProvider.complete(prompt)` call before `AskInsuranceKnowledgeUseCase` ever sees a result, so
the caller experiences it as one (possibly slower) call, not as visible retries. There is
deliberately no *additional* application-level retry loop around the whole use case: `AskInsuranceKnowledgeUseCase`
already writes an `AuditRecord` with `outcome = ERROR` on every failure path (FASE 9) and returns a
clear 502/503 to the caller - a second retry layer on top of Spring AI's own would mostly add
latency and duplicate audit records for the same logical request, not additional resilience.

## 6. Tests

`OpenAiLlmAdapterTest`/`AnthropicLlmAdapterTest`: a 4xx (`HttpClientErrorException`) throws
`PermanentProcessingException`; any other `RuntimeException` still throws
`TransientProcessingException` (regression-checked, unchanged behaviour).
`GlobalExceptionHandlerTest`: `PermanentProcessingException` maps to `502` with its error code
intact.

## 7. Current limitations

- No circuit breaker (e.g. resilience4j `CircuitBreaker`) - a PoC-scale, single-instance
  application talking to one configured provider does not yet have the traffic volume that a
  circuit breaker protects against; Spring AI's bounded retry (section 3) is judged sufficient at
  this scale.
- No fallback provider on failure (e.g. auto-switching OpenAI → Anthropic mid-request) - provider
  selection remains a static `insurance-ai.ai.provider` configuration value (FASE 5/7), not a
  runtime failover decision.
- `HttpClientErrorException` classification assumes Spring AI's HTTP clients surface 4xx as this
  standard Spring Web exception type; if a future Spring AI version wraps errors differently, the
  fallback `RuntimeException` catch still classifies it as transient (the pre-FASE-11 behaviour),
  so this is a strict refinement, never a regression.

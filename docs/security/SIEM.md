# Structured Security Event Logging (FASE 23)

Status: living document. Covers what actually exists today - structured security event logging
to stdout - and draws an explicit, honest line at what does not exist: a SIEM.

## 1. What this is, and what it is not

**This is structured security event logging. This is NOT SIEM integration.**

A SIEM (Security Information and Event Management system - e.g. Splunk, Elastic Security,
Microsoft Sentinel) ingests, correlates, alerts on, and retains security events from many
sources. No such system is deployed, configured, or connected to in this PoC. What exists is the
first half of the pipeline a real SIEM integration would need:

```
application code
   |
   v
SecurityEvent (domain.security) / GatewaySecurityEvent (gateway module)
   |
   v
SecurityEventPort (outbound port)          -- backend only, see section 4
   |
   v
SecurityEventLogger / GatewaySecurityEventLogger   -- infrastructure adapter
   |
   v
"security-events" SLF4J logger -> structured JSON line -> stdout
   |
   v
[NOT IMPLEMENTED] external log collector (Filebeat/Fluentd/Vector/...)
   |
   v
[NOT IMPLEMENTED] real SIEM (Splunk/Elastic Security/Sentinel/...)
```

Every log line above the two `[NOT IMPLEMENTED]` steps is real: genuinely structured, genuinely
emitted for every real occurrence of the event types below, and genuinely data-minimized (see
`SecurityEvent`'s Javadoc). Nothing below that line exists. A deployment that wants a real SIEM
needs to point a log collector at container stdout (or the `security-events` logger's own file/
syslog appender, configured separately in log routing config) and ship it onward - that wiring is
explicitly out of scope here, not silently assumed.

## 2. Why two separate loggers (backend and gateway)

The backend (`SecurityEventLogger`, package `infrastructure.security`) and the gateway
(`GatewaySecurityEventLogger`, package `gateway`) are two independent Maven modules/deployables -
the gateway has no dependency on backend code (see `docs/architecture/API_GATEWAY.md`). Each
therefore has its own event record (`domain.security.SecurityEvent` in the backend,
`GatewaySecurityEvent` in the gateway) and its own logger, deliberately kept field-compatible
(same `@timestamp`/`event.kind`/`event.category`/`event.type`/`trace.id`/`service.name`/... naming)
so a future log collector can treat both uniformly by `service.name`, without either module
depending on the other's code.

## 3. Event types

The minimum set the brief requires, defined in `domain.security.SecurityEventType` (backend):

| Type | Raised by | Outcome |
|---|---|---|
| `AUTHENTICATION_FAILURE` | `SecurityErrorHandler.commence` (backend) | `DENIED` |
| `AUTHORIZATION_DENIED` | `SecurityErrorHandler.handle` (backend) | `DENIED` |
| `INVALID_TOKEN` | defined, not currently raised - see section 6 | - |
| `TOKEN_EXPIRED` | defined, not currently raised - see section 6 | - |
| `RATE_LIMIT_EXCEEDED` | `RateLimitingFilter` (**gateway**, own `GatewaySecurityEventType`) | - |
| `WAF_BLOCK` | never raised from backend/gateway - see section 5 | - |
| `PII_DETECTED` | `AskInsuranceKnowledgeUseCase.ask` (question and answer, separately) | `DETECTED` |
| `PROMPT_INJECTION_BLOCKED` | `AskInsuranceKnowledgeUseCase.ask` | `BLOCKED` |
| `SECURITY_CONFIGURATION_ERROR` | defined, not currently raised - no real configuration-error
  path exists yet to wire it to (would need a genuine occurrence, not a synthetic one) | - |
| `AUDIT_ACCESS` | `AuditController` (both endpoints) | `DETECTED` |

`AUTHENTICATION_FAILURE` at the backend covers `SecurityErrorHandler.commence`, which Spring
Security's OAuth2 Resource Server invokes for every 401 cause (missing token, malformed token,
bad signature, expired token, wrong issuer) - `INVALID_TOKEN`/`TOKEN_EXPIRED` are defined as
distinct, more specific types for a future refinement that inspects the specific
`AuthenticationException` subtype, but today all of them collapse to the single
`AUTHENTICATION_FAILURE` type with the exception class name as `event.reason` (e.g.
`InvalidBearerTokenException`), which already distinguishes the cause without new code - adding
the finer-grained types is a documented gap, not a silent one.

No `ADMIN_OPERATION` type exists: no endpoint in this codebase performs an operation that is
meaningfully "administrative" beyond what `GOVERNANCE_WRITE` already covers (creating/activating
an AI system, prompt, or risk assessment) - adding a redundant event type for the same action the
governance write path already represents would duplicate signal, not add it.

## 4. Why the backend uses a port and the gateway does not

The backend is a hexagonal/DDD architecture with an `ArchitectureTest` (ArchUnit) that mechanically
enforces the application layer never depending on `infrastructure` or `adapters` (see
`docs/adr/ADR-001-HEXAGONAL-ARCHITECTURE.md`). `AskInsuranceKnowledgeUseCase` (application layer)
needs to raise security events, so it depends on `ports.outbound.SecurityEventPort` - an
interface - never on the concrete `infrastructure.security.SecurityEventLogger`. The concrete
logger implements that port and is wired by Spring; `SecurityErrorHandler` and `AuditController`
also depend on the port (not the concrete class) for the same testability/architecture reasons,
even though the layering rule does not strictly require it of them.

The gateway is a flat Spring Cloud Gateway application with no hexagonal layering (see
`docs/architecture/API_GATEWAY.md`) - `RateLimitingFilter` depends directly on
`GatewaySecurityEventLogger`, which is the only implementation and always will be for a
single-purpose gateway module.

## 5. WAF events are explicitly out of scope here

**WAF security events are not raised from backend or gateway code, and never will be.** The WAF
(ModSecurity + OWASP CRS, see `docs/adr/ADR-016-WAF-EDGE.md`) already produces its own detailed
audit log for every rule match (SQLi, XSS, scanner detection, path traversal, oversized body,
etc.) via ModSecurity's native audit logging - duplicating that signal by having the backend or
gateway guess at what the WAF blocked (which they cannot see, since a WAF-blocked request never
reaches them) would be either impossible (no visibility) or redundant (re-deriving what
ModSecurity already logs, with less detail).

**WAF security events and backend/gateway security events are two different log streams today,
not one unified stream.** A future SIEM integration would ingest both -
`security-events`-logged JSON lines from the backend/gateway containers' stdout, and
ModSecurity's audit log from the WAF container - and correlate them by `trace.id`/timestamp/
source IP, since a single malicious request may appear in one, the other, or (for a
WAF-block) only the WAF's own log. That correlation is not implemented here; it is a property a
real log collector configuration would need to establish (e.g. by ensuring `X-Trace-Id` is
captured by the WAF's own logging directives too - currently not configured either, a further
documented gap).

## 6. Fields

Every event carries, when applicable (see `SecurityEvent`'s constructor Javadoc for exactly which
fields are legitimately null for which event types):

| Field | Source | Notes |
|---|---|---|
| `@timestamp` | `Instant.now()` | ISO-8601 |
| `event.kind` | constant `"event"` | ECS convention |
| `event.category` | constant `"security"` | ECS convention |
| `event.type` | `SecurityEventType`/`GatewaySecurityEventType` enum name | |
| `event.outcome` | `SecurityEventOutcome` enum name (`BLOCKED`/`DENIED`/`DETECTED`/`ERROR`) | backend only - the gateway's smaller event set doesn't need this discriminator today |
| `trace.id` | the request's trace ID (MDC/`X-Trace-Id`) | correlates across the WAF/gateway/backend hop chain, see section 5's caveat |
| `service.name` | constant per module (`insurance-ai-backend`/`insurance-ai-gateway`) | |
| `user.id` | the authenticated principal's JWT `sub` claim, or `null` | never a name/email - see `docs/security/IAM_ARCHITECTURE.md` for what the `sub` claim actually is |
| `http.request.method` | the servlet/exchange request | omitted when not applicable (e.g. `PII_DETECTED`, which originates mid-pipeline, not from a fresh request) |
| `url.path` | the servlet/exchange request path | never query parameters (may carry PII) |
| `http.response.status_code` | the HTTP status actually returned | |
| `event.reason` | a short, pre-defined, bounded category string | see section 7 |

## 7. What `event.reason` is, and is deliberately never

`event.reason` is always one of: an exception's simple class name (e.g.
`AccessDeniedException`, `AuthenticationCredentialsNotFoundException`), a fixed literal
(`"question"`/`"answer"` for `PII_DETECTED`, distinguishing input from output PII), a
comma-joined list of named prompt-injection pattern categories (e.g. `"ignore_instructions"` -
see `PromptInjectionAssessment`'s Javadoc: these are named categories, never the matched text
itself), or a rate-limit route-family name (`"AUDIT"`, `"EVALUATION"`, ...).

**`event.reason` is never**: `exception.getMessage()`, the raw question, the raw answer, a
detected PII value, an `Authorization` header, a JWT (access or refresh), a password, an API key,
or any other secret. `SecurityEventLoggerTest` asserts this negatively (the logger's own output
never contains `Authorization`/`Bearer `/`refresh_token`/`password`/a JWT-shaped string) as a
regression guard, in addition to every call site only ever passing bounded category strings in
the first place.

## 8. Verified call sites (FASE 23 requirement: real call sites, not just the logger in isolation)

| # | Event | Test | What it proves |
|---|---|---|---|
| 1 | `AUTHENTICATION_FAILURE` | `SecurityErrorHandlerTest.commenceEmitsAnAuthenticationFailureEvent` | a real 401 (`AuthenticationEntryPoint.commence`) emits the event with method/path/status/reason |
| 2 | `AUTHORIZATION_DENIED` | `SecurityErrorHandlerTest.handleEmitsAnAuthorizationDeniedEventCarryingTheAuthenticatedPrincipal` | a real 403 (`AccessDeniedHandler.handle`) emits the event with the authenticated principal |
| 3 | `RATE_LIMIT_EXCEEDED` | `RateLimitingFilterTest.a429EmitsARateLimitExceededSecurityEvent` (gateway) | a real 429 from the Bucket4j filter emits the event |
| 4 | `PROMPT_INJECTION_BLOCKED` | `AskInsuranceKnowledgeUseCaseTest.aPromptInjectionAttemptInTheQuestionIsBlockedWithoutCallingRetrievalOrTheLlm` | a real blocked question emits the event with the matched pattern categories |
| 5 | `PII_DETECTED` (question) | `AskInsuranceKnowledgeUseCaseTest.detectedPiiInTheQuestionDoesNotBlockTheRequest` | input PII emits the event without blocking |
| 6 | `PII_DETECTED` (answer) | `AskInsuranceKnowledgeUseCaseTest.aGroundedAnswerContainingDetectedPiiSurfacesThePiiDetectedFlag` | output PII emits a separately-reasoned event |
| 7 | `AUDIT_ACCESS` | `AuditControllerTest` (both endpoints) | every audit lookup - successful or not - emits the event, since the probe attempt itself is what matters |
| 8 | `SECURITY_CONFIGURATION_ERROR` | **not covered** | no genuine configuration-error path exists to trigger this from - see section 3 |

Real end-to-end (through Docker, not just unit tests) evidence for items 1-3 also exists from the
FASE 17/19-23 authorization-matrix and rate-limit verification runs (see
`FASE_23_REPORT.md`) - real HTTP 401/403/429 responses were observed through
WAF -> Gateway -> Backend, matching what the unit tests above assert about the events those same
code paths raise.

## 9. Never logged

Consistent with `SecurityEvent`'s own Javadoc: an `Authorization` header, an access or refresh
token, a password, a full prompt, a full LLM answer, or raw PII. `event.reason` is always a short,
pre-defined category string (section 7) - never free-form user input echoed back.

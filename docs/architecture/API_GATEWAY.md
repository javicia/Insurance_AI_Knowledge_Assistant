# API Gateway (FASE 16/19/21)

Status: living document, describes the real `gateway/` module - a genuinely independent Spring
Cloud Gateway (WebFlux) Maven project, sharing no code with `backend/` or `frontend/`.

## 1. What the gateway is responsible for

- **Routing**: five explicit routes (`gateway/src/main/resources/application.yaml`), one per real
  backend controller (`/api/chat/**`, `/api/documents/**`, `/api/governance/**`, `/api/audit/**`,
  `/api/evaluation/**`), each restricted to the HTTP methods that controller actually supports
  (`Method=POST` for chat, `Method=GET` for audit, `Method=GET,POST` for the rest). No catch-all
  route exists.
- **CORS**: `spring.cloud.gateway.server.webflux.globalcors`, restricted to the frontend's real
  origin (`INSURANCE_AI_ALLOWED_ORIGIN`).
- **JWT validation** (`GatewaySecurityConfiguration`, FASE 19): defense in depth - a request the
  gateway itself already knows carries no valid token never reaches the backend at all. Coarse
  (authenticated or not), not a duplicate of the backend's fine-grained per-endpoint authority
  matrix (FASE 17) - see `docs/security/IAM_ARCHITECTURE.md`.
- **Trusted header stripping** (`TrustedHeaderStrippingFilter`, FASE 19): unconditionally removes
  `X-User`/`X-Roles`/`X-Principal`/`X-Authenticated-User` from every incoming request, at the
  highest possible filter precedence, before any other filter runs.
- **Correlation ID** (`CorrelationIdGlobalFilter`, FASE 16): assigns `X-Trace-Id` at the true edge
  if the client didn't supply one.
- **Request size limits**: `RequestSize` filter, 15MB, on every route.
- **Timeouts**: `connect-timeout: 5000ms`, `response-timeout: 30s` toward the backend.
- **Rate limiting** (`RateLimitingFilter`, FASE 21): see section 2.

## 2. Rate limiting

Per-`(authenticated subject, route family)` token bucket (`io.github.bucket4j`), not a shared
global counter - a burst-tolerant, steadily-refilling limit. Default policy (requests/minute):

| Route family | Limit |
|---|---|
| `/api/chat` | 60 |
| `/api/documents` | 10 |
| `/api/governance` | 30 |
| `/api/audit` | 30 |
| `/api/evaluation` | 5 |

An unauthenticated caller (should not normally happen - every `/api/**` route requires
authentication) falls back to a per-remote-address key rather than skipping the limit entirely.
Exceeding the limit returns `429` with `Retry-After` and `X-RateLimit-Remaining: 0`; every response
in that route family also carries `X-RateLimit-Limit`/`X-RateLimit-Remaining`.

**In-memory, not Redis, for this PoC.** A single gateway instance has no state to coordinate - a
`ConcurrentHashMap<String, Bucket>` is correct and requires no extra infrastructure. This is
explicitly a `LOCAL_HA_SIMULATION`-adjacent limitation, not a hidden one: the moment a second
gateway instance is added, each instance enforces its own independent limit, silently multiplying
the real ceiling a client experiences. The documented migration path is `bucket4j-redis`'s
`LettuceBasedProxyManager`, a drop-in replacement for the `buckets` map with no change to the
bucket-construction/consumption logic in `RateLimitingFilter` - not a redesign, a backend swap.

**A real bug was found and fixed while testing this filter** (not merely by inspection): the
original `switchIfEmpty` fallback (for "no security context found") was chained after the entire
downstream `flatMap`, not scoped to only the context-lookup step. Since a successfully-routed
request's completion signal is itself empty (a `Mono<Void>`), this caused `applyLimit` to run
**twice per request** - silently consuming two tokens instead of one, and crashing outright with
`UnsupportedOperationException` once a response had already been committed once (headers become
read-only after commit). `RateLimitingFilterTest.allowsRequestsWithinTheLimitThenReturns429WithRetryAfter`
caught this by asserting the exact number of requests that reach the downstream chain, not just
that *some* request eventually got a `429`. Fixed by scoping `switchIfEmpty` to only the subject
resolution `Mono<String>`, before it is `flatMap`ped into `applyLimit`.

## 3. What the gateway deliberately does not do

- It does not know Document/RAG/Governance/Audit domain concepts - only route paths, methods, and
  cross-cutting technical policy.
- It does not duplicate the backend's fine-grained authorization matrix (see section 1).
- It does not propagate a custom identity header - the original JWT (forwarded unchanged, Spring
  Cloud Gateway's default header-forwarding behavior) is the one and only principal-propagation
  mechanism end to end; see `docs/adr/ADR-016-WAF-EDGE.md` for the reasoning against inventing a
  second, forgeable one.

## 4. Testing

`CorrelationIdGlobalFilterTest`, `TrustedHeaderStrippingFilterTest`, `RateLimitingFilterTest` (4
cases: burst-then-429 with `Retry-After`, independent buckets per user, independent policies per
route, routes outside the five known families untouched), `GatewayApplicationTests` (real context
load against the real `application.yaml`, including the reactive JWT decoder and rate limiter
beans - no running Keycloak needed, since `NimbusReactiveJwtDecoder` is lazy).

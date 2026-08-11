# ADR-014: Frontend/Backend/Gateway Separation

## Status

Accepted — FASE 16.

## Context

FASE 15 (ADR-013) deliberately packaged Angular and Spring Boot into a single container/artifact
for a PoC's simplicity: one build, one image, one deployable, no CORS. FASE 16 begins an enterprise
hardening program whose end-state architecture (IAM via a separate identity provider, an API
Gateway, a WAF, independent scaling, independent release cycles for frontend vs. backend) requires
the opposite: frontend and backend as genuinely separate products, sharing no code, no build, no
artifact - the brief is explicit and non-negotiable on this point ("ESTO ES OBLIGATORIO").

## Decisions

**1. Three independent Maven/npm projects, three independent Docker images, one docker-compose
orchestrating all of them.** `backend/` (Spring Boot API, no static content), `frontend/` (Angular,
built and served by its own nginx image, no JVM), `gateway/` (a new Spring Cloud Gateway module).
None share a `pom.xml`, `package.json`, `Dockerfile`, or build step. `backend/`'s
`./mvnw clean verify` never touches `frontend/` or `gateway/`; `frontend/`'s `npm run build` never
touches `backend/` or `gateway/`.

**2. `SpaWebConfiguration` removed, not adapted.** The backend no longer serves any static content
or performs any SPA-fallback resolution - that responsibility (serving `index.html`, falling back
to it for client-side routes, cache headers for hashed assets) moves entirely into
`frontend/nginx.conf`. The backend's `GlobalExceptionHandler`'s `NoResourceFoundException` handling
is retained (an unmapped path still needs a real `404`, now via Spring Boot's own default,
now-empty static-resource handler rather than the removed custom resolver) and re-verified by a
renamed `ApiRoutingIntegrationTest` (formerly `SpaWebConfigurationIntegrationTest`).

**3. `PUBLIC_API_BASE_URL` as the frontend's one external fact, injected at container startup, not
build time.** Angular's build output is static JavaScript with no server-side templating, so a
build-time-only base URL would force a separate image per environment. Instead,
`frontend/docker-entrypoint.sh` (an nginx `/docker-entrypoint.d/` startup hook) generates
`env.js` from the `PUBLIC_API_BASE_URL` environment variable via a plain shell heredoc (`envsubst`
was considered and rejected only because a heredoc needs no extra binary in the already-minimal
`nginx:alpine`-derived image); `index.html` loads it before Angular bootstraps;
`core/config/api.config.ts` reads `window.__env.PUBLIC_API_BASE_URL` once at module load. The same
built image is therefore deployable against any environment's gateway URL without a rebuild - a
standard "build once, configure per environment" pattern, not bespoke to this project.
`public/env.js` (committed, empty base URL) is the local-dev default so `ng serve` behaves
identically to before this phase, via the newly-added `proxy.conf.json` (see decision 5).

**4. `nginxinc/nginx-unprivileged`, not plain `nginx`.** The frontend container needed a genuinely
non-root process (not a worker-only privilege drop, which is what plain `nginx` gives) from day
one rather than retrofitting it in the later container-hardening phase - the unprivileged variant
listens on `8080` by default and needs no hand-rolled `setcap`/user workaround.

**5. `proxy.conf.json` actually created - a real gap found and fixed, not invented scope.** FASE
15's `README.md` documented `npm start` as using a `proxy.conf.json` to forward `/api`/`/actuator`
to the backend during local development; the file never actually existed, and `angular.json`'s
`serve` target had no `proxyConfig` option set. Both are now real. This is called out explicitly
because this ADR's own governing brief prohibits presenting undocumented gaps as already-solved -
this one was found by inspection while wiring up the new local-dev story, not invented as new scope.

**6. Backend port still published to the host in `docker-compose.yml`.** Real network segmentation
(removing direct host access to the backend, routing every external call through the
gateway/WAF only) is FASE 40's explicit scope, not this phase's - keeping `8080` published here
preserves direct Swagger UI / `curl` access during this transitional phase without pretending
segmentation is already done.

**7. Spring Cloud Gateway (WebFlux variant), not a hand-rolled reverse proxy.** A new,
`spring-boot-starter-parent`-versioned Maven module (`gateway/pom.xml`, `spring-cloud-dependencies`
BOM aligned to the same Spring Boot generation as `backend/`) with explicit path-based routes to
`backend/`'s five controllers (no wildcard catch-all - the gateway's routing table is the one place
a new backend endpoint must be deliberately exposed, not automatically). A single
`CorrelationIdGlobalFilter` assigns `X-Trace-Id` at the true edge if the client didn't supply one,
so a request's trace id covers its full journey including the gateway hop - `backend/`'s existing
`TraceIdFilter` is unchanged and simply reuses whatever value it receives. Full routing,
rate-limiting, and security-token-propagation behavior is FASE 19/21's scope; this phase proves the
module boots as a real, independently deployable Spring Boot application with a working route
table (verified by `GatewayApplicationTests`, a real context-load test against the real
`application.yaml`).

## Consequences

- `docs/frontend/FRONTEND_ARCHITECTURE.md` and `UI_GUIDELINES.md` (FASE 15) remain accurate for the
  Angular application's internal structure; their references to single-container packaging are now
  historical and superseded by this ADR and `README.md`'s updated Quick Start.
- The backend test count moved from 286 to 283 (net effect of this phase): `SpaWebConfigurationIntegrationTest`
  (6 cases, SPA-specific) was replaced by `ApiRoutingIntegrationTest` (3 cases, API-only) - a
  reduction in count that reflects removed responsibility, not weakened coverage; every case that
  is still a real backend behavior (unmapped path 404, real endpoint 200, wrong-method 405) is
  still tested against the real Spring MVC context.
- `docker-compose.yml` now orchestrates 6 services (`postgres`, `kafka`, `kafka-ui`, `backend`,
  `gateway`, `frontend`) instead of 4 - each with its own healthcheck, its own `depends_on`
  condition, and its own container name.
- No code is shared between `backend/`, `frontend/`, and `gateway/` - confirmed by each having its
  own independent build/test cycle with zero cross-directory imports, and by CI-equivalent
  verification run separately per module in this phase's own validation.

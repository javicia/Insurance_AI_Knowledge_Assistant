# ADR-013: Frontend Architecture — Angular SPA, Single-Container Packaging

## Status

Accepted — FASE 15.

## Context

FASE 15 needs a real, professional UI for the RAG insurance assistant, covering the assistant
chat, document ingestion status, and read views onto governance/audit/evaluation data already
exposed by the backend (FASE 8-9). The brief is explicit that this is an internal enterprise tool,
not a public product: no separate frontend team's tooling assumptions should be imported wholesale
(no NgRx-scale state, no microfrontend split, no i18n/dark-mode investment) if the backend and
brief don't call for it. Two framework-adjacent constraints shape every decision below: (1) the
backend's hexagonal architecture (`domain`/`application`/`ports`/`adapters`/`infrastructure`) must
stay completely unaware of Angular, and Angular must stay completely unaware of
Postgres/Kafka/Flyway/Hibernate — the only contract between them is the REST API; (2) the project
already ships as a single Spring Boot process (`./mvnw spring-boot:run`, one Docker image) — FASE
15 should preserve that, not turn deployment into two coordinated services.

## Decisions

**1. Angular 22 (standalone components, no `NgModule`), not React/Vue.** The backend is Java/Spring
already; Angular's opinionated, batteries-included structure (router, forms, HTTP client, DI, CLI
scaffolding, built-in test runner) minimizes the number of ecosystem choices a small internal tool
needs to make, matching the project's general preference (seen throughout the backend ADRs) for
well-supported framework defaults over assembling a bespoke toolchain. Standalone components
(no `NgModule` boilerplate) and signals are Angular's current recommended default as of v22, not a
legacy pattern being carried forward.

**2. Angular Material + CDK, no additional UI framework.** M3 theming plus CDK's
`BreakpointObserver` covers every UI need this app has (buttons, tables, expansion panels,
sidenav, form fields, snackbar-free error surfacing via inline banners) without a second
component library's CSS colliding with the custom design tokens in `styles.scss` (see
`docs/frontend/UI_GUIDELINES.md`). Rejected: a headless/utility-first stack (e.g. Tailwind +
Radix) — strictly more assembly work for a five-screen internal tool with no bespoke visual
identity requirement beyond "not a chatbot demo."

**3. Signals for local/service state, no NgRx.** State here is small and mostly
request/response-shaped (chat messages, per-session upload tracking, page-level loaded lists) —
exactly the case Angular's own signals were designed to cover without a store, actions, reducers,
selectors, and effects. `RxJS` is kept, but scoped to where it earns its place: `HttpClient`'s
`Observable` return type, `DocumentTrackerService`'s interval-based status polling, and
`forkJoin` for the governance page's parallel reads. Brief section 29 explicitly calls out NgRx as
unnecessary at this scale; introducing it would be the same overengineering the backend ADRs
(e.g. ADR-010 decision 2, ADR-012) repeatedly reject for the backend.

**4. `core`/`shared`/`layout`/`features` directory split, one feature per bounded UI area.**
Mirrors the backend's own discipline of drawing explicit boundaries (hexagonal architecture,
ADR-001/002) in frontend terms: `core` holds cross-cutting singletons (interceptors, models,
services), `shared` holds presentational components with no feature knowledge (`StatusBadge`,
`LoadingIndicator`, `TechnicalDetails`, `EmptyState`), and each of the five `features/*` folders
(`assistant`, `documents`, `governance`, `audit`, `evaluation`) owns its own pages/components/
services and is lazy-loaded via `loadComponent` — no cross-feature imports except through
`core`/`shared`. This keeps any one feature's growth from leaking into another's, the same
motivation as the backend's module boundaries, without introducing a heavier structure (e.g. Nx
monorepo, microfrontends) this five-route app doesn't need.

**5. Models are read from real backend DTOs, never invented.** Every interface in
`core/models/*.model.ts` was built by reading the actual `adapters.inbound.rest.**` response
types, not guessed from the brief's prose. Two consequences: no endpoint is called that the
backend doesn't genuinely expose (most notably, there is no "list all documents" endpoint, so
`DocumentTrackerService` deliberately tracks only the current browser session's uploads rather
than presenting a fabricated persisted library — see that service's own documentation); and
`GovernanceService` implements only the read endpoints (`list*`/`get*`) the UI actually calls,
leaving the backend's administrative write endpoints (register/activate/draft/approve) out of
scope for this employee-facing read UI rather than stubbing them out unused.

**6. `RagAnswer.blocked` — a minimal, justified backend change, not a frontend workaround.**
Before FASE 15, `noAnswer()` and `blocked()` both produced an identical
`grounding.status == NOT_GROUNDED`, distinguishable only by matching the free-text `answer`
string — fragile, and not a real API contract a frontend should depend on. Rather than
string-matching client-side, a `boolean blocked` field was added to `RagAnswer` on the backend,
documented and tested (`RagAnswerTest`, `AskInsuranceKnowledgeUseCaseTest`) with the full backend
suite re-verified green before the frontend was built against it. This is the one backend change
FASE 15 required, and it was made for correctness (a real, named field beats pattern-matching
free text), not convenience.

**7. Single-origin, same-container deployment — Angular's build output copied into Spring Boot's
`static/` classpath folder, not a second container/process.** The Docker multi-stage build
compiles Angular (`npm ci && npm run build`) and copies `dist/frontend/browser/` into
`src/main/resources/static/` before `mvn package` runs, so Spring Boot's own static-resource
handling serves it — no reverse proxy, no CORS configuration, no second exposed port, no second
health check to wire up. `./mvnw clean verify` itself stays Node-independent (the frontend build
only happens inside the Docker image build, never as a Maven plugin step) so backend-only
contributors and backend CI are unaffected by the frontend toolchain. Rejected: a separate Nginx-
or Node-served frontend container behind a reverse proxy — genuine architecture for a
multi-team, independently-scaled product, not for a single-instance internal PoC that already
runs as one process.

**8. `SpaWebConfiguration` (`WebMvcConfigurer` + custom `PathResourceResolver`), not a servlet
filter or a catch-all `@Controller` mapping.** Registers on `/**`, falls back to `index.html` for
any path Angular's router owns, but explicitly refuses that fallback for anything under `api/` or
`actuator/` so an unmapped API path still produces a real `404` rather than a silently-succeeding
HTML page. This relies on Spring's existing handler-mapping precedence (every `@RestController`
is always consulted before the static-resource chain) rather than reimplementing routing logic,
and is verified against real MVC precedence by `SpaWebConfigurationIntegrationTest`, not just unit
-tested against the resolver in isolation — this test caught a genuine bug during development
(`NoResourceFoundException` wasn't mapped by `GlobalExceptionHandler` and fell through to a
generic 500; fixed by adding an explicit 404 handler for it).

**9. Vitest (Angular 22's `@angular/build:unit-test` default), not Karma/Jasmine.** Karma requires
a real or headless browser launcher and is being phased out across the Angular ecosystem; Vitest
is the framework's own new default builder and needed no extra configuration. The cost was a
one-time adaptation (Jasmine-only matchers like `toBeTrue()` don't exist in Vitest's
`@vitest/expect`; `fakeAsync`/`tick()` need `zone.js/testing`, not wired into this builder — fixed
by using `vi.useFakeTimers()` instead), documented in `docs/frontend/FRONTEND_ARCHITECTURE.md`
section 11, not a reason to fall back to the older toolchain.

**10. No E2E browser-automation suite (Playwright/Cypress).** Evaluated and deliberately deferred:
a five-route internal PoC gets most of the same confidence from the existing 47-test unit/
component/interceptor suite plus scripted manual verification against the real Docker Compose
stack (`docs/demo/DEMO_GUIDE.md`). Adding a full E2E framework and its own CI lane is real ongoing
maintenance cost the brief's "no overengineering" principle (section 61) weighs against at this
scale — documented as a known limitation, not silently omitted.

## Consequences

- The backend gained exactly one field (`RagAnswer.blocked`) and one new configuration class
  (`SpaWebConfiguration`) plus its exception-handler addition — `domain` and `application` remain
  completely unaware Angular exists; the dependency only flows one way, through the REST contract.
- The frontend has no build-time or run-time dependency on backend internals (no generated
  OpenAPI client, no shared DTO package) — `core/models/*.model.ts` are hand-written mirrors of
  the real response shapes, kept honest by `*.service.spec.ts` HTTP-contract tests rather than by
  codegen.
- Deployment stays a single Docker image and a single running process, matching every prior phase
  of this project's "one Spring Boot instance" packaging story — FASE 15 did not introduce a
  second container, port, or process supervisor.
- `docs/frontend/FRONTEND_ARCHITECTURE.md` and `docs/frontend/UI_GUIDELINES.md` are the canonical
  references for this phase's structure and visual system respectively; this ADR records why,
  those documents record what.

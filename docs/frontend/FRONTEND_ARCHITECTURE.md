# Frontend Architecture (FASE 15)

Status: living document. See `docs/adr/ADR-013-FRONTEND-ARCHITECTURE.md` for the decisions
behind this design, `docs/frontend/UI_GUIDELINES.md` for the visual design system.

## 1. Stack

Angular 22.1.3 (standalone components, no `NgModule`), TypeScript strict mode, Angular Material
22.1.1 + CDK (M3 theming, no additional UI framework - brief section 4/23), RxJS for HTTP/async
flows, Signals for local component/service state. No NgRx (brief section 29 - not needed at this
scale). No SSR (a purely client-rendered SPA is sufficient for an internal tool).

## 2. Directory structure

```
frontend/src/app/
  core/                    Cross-cutting, singleton concerns
    config/                API base URL, trace header name constants
    interceptors/          traceInterceptor, errorInterceptor (functional HttpInterceptorFn)
    models/                TypeScript interfaces mirroring real backend DTOs exactly
    services/               ChatService, DocumentService, GovernanceService, AuditService,
                            EvaluationService, HealthService, SystemStatusService,
                            TraceContextService
  shared/                  Reusable, feature-agnostic presentational components
    components/            StatusBadge, LoadingIndicator, TechnicalDetails, EmptyState
  layout/                  Application chrome
    shell/ header/ sidebar/
  features/                One folder per bounded UI area, each lazy-loaded
    assistant/             The main screen - chat
    documents/              Upload + session-tracked ingestion status
    governance/              AI System / Model / Prompt / Risk Assessment read views
    audit/                   Audit record list + detail panel
    evaluation/               Evaluation run list + metrics + per-case results
  app.config.ts            Providers: router, HttpClient+interceptors, animations, Material icons
  app.routes.ts             Lazy-loaded routes, one per feature
```

Each feature owns its own `pages/`, `components/`, `services/`, `models/`, `utils/` - no
cross-feature imports except through `core`/`shared`.

## 3. Routing

`app.routes.ts` lazy-loads each feature page via `loadComponent`. `''` redirects to
`/assistant` (the home screen, brief section 24 - never a generic dashboard). The wildcard
route also redirects to `/assistant` rather than a dedicated 404 page (small, deliberate scope
choice for a 5-route internal tool).

## 4. State management

Signals for all local/service state (`ChatSessionService.messages`, `DocumentTrackerService.uploads`,
page-level `signal()`s for loaded data) - no NgRx, no global store. RxJS is used exactly where it
adds value: HTTP calls (`HttpClient` returns `Observable`), polling (`interval`/`switchMap` in
`DocumentTrackerService`), and combining parallel requests (`forkJoin` in `GovernancePage`).
Subscriptions from one-shot HTTP calls are not manually unsubscribed (Angular's `HttpClient`
completes automatically after emitting); the only long-lived subscription
(`DocumentTrackerService`'s per-document poller) explicitly unsubscribes itself once a document
reaches a terminal status (`EMBEDDED`/`FAILED`) - see that service's Javadoc-equivalent comment.

## 5. API integration - real backend contract only

Every model in `core/models/*.model.ts` was built by reading the actual backend DTOs
(`adapters.inbound.rest.**`), not invented or guessed. No endpoint is called that the backend
does not genuinely expose - most notably, **there is no "list all documents" endpoint** (only
`POST /api/documents` and `GET /api/documents/{id}` exist), so the Documents page deliberately
shows only documents uploaded during the current browser session with live status polling, not a
persisted document library (see `DocumentTrackerService`'s Javadoc).

`GovernanceService` only implements the read endpoints the frontend actually uses
(`list*`/`get*`) - the backend's write endpoints (register/activate/draft/approve) are
administrative governance-workflow actions out of scope for this employee-facing read UI, not
omitted by oversight.

## 6. `RagAnswer.blocked` - a minimal, justified backend change

Before FASE 15, `RagAnswer.noAnswer()` and `RagAnswer.blocked()` both produced an identical
`grounding.status == NOT_GROUNDED` with only the free-text `answer` message distinguishing "no
relevant evidence" from "blocked by the prompt-injection guardrail" - the frontend would have had
to pattern-match against an exact message string to render two different UX states correctly,
which is fragile and not a real API contract. A `boolean blocked` field was added to `RagAnswer`
(backend), documented, tested (`RagAnswerTest`, updated `AskInsuranceKnowledgeUseCaseTest`), and
the full backend suite re-verified green before the frontend was built against it. See that
record's Javadoc for the full reasoning.

## 7. Error handling

`errorInterceptor` normalizes every failed HTTP call into one `ApiError` shape
(`{status, code, message, traceId}`), whether the failure came from the backend's real
`ErrorResponse` contract (`GlobalExceptionHandler`) or a transport-level failure that never
reached the backend (network down, malformed non-JSON body from an unmapped Spring default error
page). No component ever sees a raw `HttpErrorResponse`, a stack trace, or a Java exception name.

## 8. Traceability

`traceInterceptor` captures the `X-Trace-Id` response header (echoed by `TraceIdFilter` on every
request) into `TraceContextService`. `RagAnswer.traceId`/`ErrorResponse.traceId` are also
threaded through into the UI (`TechnicalDetails` component) - never generated client-side as a
substitute for a real backend trace id.

## 9. Angular ↔ Spring Boot integration (single origin, single container)

- **Same-origin API calls**: every service calls `/api/...` (relative path,
  `core/config/api.config.ts`), never a hardcoded host/port - works identically whether Angular
  runs via `ng serve` (with `proxy.conf.json` forwarding `/api`/`/actuator` to the backend) or
  packaged inside the same Spring Boot process.
- **Production packaging**: the Docker multi-stage build (`Dockerfile`) compiles Angular
  (`npm ci && npm run build`) and copies `dist/frontend/browser/` into
  `src/main/resources/static/` *before* `mvn package` runs - Spring Boot's own static-resource
  convention (`src/main/resources/static/**` → classpath `/`) does the rest; no custom Maven
  plugin was introduced (see ADR-013 decision on why `./mvnw clean verify` itself stays
  Node-independent).
- **SPA routing fallback**: `SpaWebConfiguration` (`infrastructure.configuration`) resolves an
  unmatched path to `index.html` so `/assistant`, `/documents`, etc. work on direct navigation
  and page refresh - but explicitly refuses to do so for anything under `api/` or `actuator/`,
  so an unmapped API path still gets a real `404`, never a silently-succeeding HTML page. This
  is enforced by `SpaWebConfigurationIntegrationTest` against the real Spring MVC handler
  mapping precedence (not just the resolver in isolation) - which caught a real bug during
  development: `NoResourceFoundException` wasn't mapped by `GlobalExceptionHandler` and fell
  through to a generic `500`; fixed by adding an explicit `404` handler for it.

## 10. Development mode

```bash
./mvnw spring-boot:run          # backend on :8080
cd frontend && npm start        # frontend on :4200, proxied to :8080 via proxy.conf.json
```

The Docker image never runs `ng serve` - it only ever serves the pre-built static output.

## 11. Testing

Vitest (Angular 22's `@angular/build:unit-test` builder default), not Karma/Jasmine - assertions
use Vitest's `expect`/`vi` API (`toBe(true)`, not Jasmine's `toBeTrue()`; `vi.useFakeTimers()`,
not `fakeAsync`/`tick()`, since `zone.js/testing` is not wired into this builder). 47 tests across
15 spec files: services (HTTP contract shape), interceptors (error normalization, trace capture),
components (all 4 `MessageBubble` states, citation rendering, composer keyboard behaviour), and
one polling-lifecycle test (`DocumentTrackerService`, fake timers).

## 12. Known limitations

- E2E browser automation **now exists** (FASE 26): a real Playwright suite in `e2e/`, driving a
  real Chromium through the WAF against the running Docker stack, including a genuine OAuth2
  Authorization Code + PKCE login - see `docs/testing/E2E_PLAYWRIGHT.md`. Its first run found six
  defects that made the deployed app unusable in a browser and that no unit test, healthcheck or
  `curl` check could see. Remaining E2E gaps: Chromium only (no cross-browser matrix), no visual
  regression, and no automated accessibility assertions (`axe`).
- **Zoneless change detection** (FASE 26): `provideZonelessChangeDetection()`. The app was
  previously configured with `provideZoneChangeDetection()` while `zone.js` was neither a
  dependency nor a declared polyfill, so it threw `NG0908` and rendered nothing in a browser. The
  application is fully signal-based, which is precisely the model zoneless targets.
- No i18n - English only, matching the project's English-only code/UI convention.
- No dark mode - evaluated and deliberately not implemented (brief section 47: "if the result
  doesn't clearly improve, don't implement it" - a single, well-executed light theme was judged
  the better use of scope for this PoC).

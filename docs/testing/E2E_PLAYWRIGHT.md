# End-to-end testing with Playwright

The `e2e/` module is a **system-level** suite: it drives a real browser against the fully deployed
`docker compose` stack. It is deliberately a separate npm module from `frontend/` — these tests
exercise the whole system through the WAF, not the Angular app in isolation, and must not be able
to import frontend source or share its build.

```
Playwright (real Chromium)
   │
   ▼
WAF :8000  (ModSecurity / OWASP CRS — the one browser-facing edge)
   ├── /        → frontend (nginx, Angular bundle)
   └── /api/**  → gateway :8082 → backend :8080 → PostgreSQL / Kafka / Keycloak
```

## Running it

```bash
docker compose up -d          # the suite does NOT start the stack itself
# wait until `docker compose ps` shows every healthcheck-bearing service healthy

cd e2e
npm install
npx playwright install chromium
npx playwright test
```

`global-setup.ts` fails the run immediately, with an actionable message, if the WAF, Keycloak, or
the API are not reachable — otherwise every test fails with an opaque connection error and the real
cause (nobody started the stack) is buried.

## Deliberate design decisions

**`baseURL` is the WAF, never the gateway or backend.** A suite that bypassed the WAF would
silently stop covering ModSecurity, the security headers, the CSP, and the gateway's routing and
rate limiting — the layers most likely to break a real browser session, and precisely the ones that
did break (see below).

**Login is a real OAuth2 Authorization Code + PKCE flow.** The browser is genuinely redirected to
Keycloak, a real login form is filled, and a real code is exchanged for a real token. Injecting a
token into storage would skip the redirect/PKCE/callback handling — exactly the part that was
broken — and would let the suite pass against an application nobody can log into.

**`workers: 1`, no retries.** The gateway enforces per-identity rate limits (5/min on
`/api/evaluation`); parallel workers sharing the seeded test user would trip them and produce 429s
that look like product bugs. Retries are omitted on purpose: a retry hides flakiness instead of
fixing it.

**Two complementary layers.** UI tests assert what a user experiences (grounded answer with
citations, no-answer state, blocked state). API-level tests assert what the *backend* enforces
(the authorization matrix), because the frontend's route guards are UX only — the security boundary
is the backend, so that is what is pinned down.

## Coverage

| Spec | Covers |
|---|---|
| `01-authentication` | redirect to Keycloak, invalid credentials, full code+PKCE login, anonymous 401, tampered token 401, sign-out |
| `02-authorization` | the full role→endpoint matrix for all four seeded users, plus identity-header spoofing |
| `03-assistant` | empty state, grounded answer with citations, no-answer, prompt injection blocked, composer disabled in flight, new chat |
| `04-navigation-and-features` | sidebar navigation, governance/documents/audit/evaluation pages, responsive layout at 390×844 |
| `05-edge-security` | security headers, CSP shape, SQLi/XSS/scanner/traversal blocking, rate limiting with `Retry-After`, no stack-trace leakage |

## Why this suite exists: six bugs nothing else caught

The first real-browser run found six defects that the unit suites (61 frontend, 344 backend),
the container healthchecks, and every `curl`-based API check had all passed straight through —
because none of them execute JavaScript, follow an OAuth redirect, or enforce a CSP.

| # | Defect | Effect | Fix |
|---|--------|--------|-----|
| 1 | `provideZoneChangeDetection()` while `zone.js` was not a dependency and `angular.json` declared no `polyfills` | `NG0908` at bootstrap — **the deployed app rendered a blank page in any browser** | switched to `provideZonelessChangeDetection()` (the app is fully signal-based) |
| 2 | Angular's critical-CSS inlining emits an inline `onload` handler | blocked by `script-src 'self'` | disabled `optimization.styles.inlineCritical` rather than weakening the CSP |
| 3 | CSP `connect-src` listed the OIDC issuer **without a trailing slash** | per CSP path-matching that allows one exact URL, so `.well-known/openid-configuration` was blocked and **login could never start** | append `/` in `docker-entrypoint.sh` |
| 4 | Keycloak `redirectUris` never included the WAF origin (`:8000`), only the pre-WAF `:8083`/`:4200` | Keycloak refused the login with `Invalid parameter: redirect_uri` | added `http://localhost:8000/*` |
| 5 | Gateway CORS `allowedOrigins` still pointed at `:8083` | **every browser API call returned 403** while `curl` got 200 | set to the WAF origin `:8000` |
| 6 | `sidenavOpened = signal(true)` | on a phone the nav drawer covered the whole screen at first load | defaults to closed |

Items 3–5 are all the same failure mode: **configuration that drifted when the WAF became the edge
in FASE 20, in places only a browser exercises.** They are the strongest argument for keeping this
suite pointed at the WAF.

## Limitations

- **Chromium only.** No cross-browser matrix; adding WebKit/Firefox projects is a config change.
- **No accessibility assertions.** Keyboard/ARIA coverage is limited to the roles used as
  selectors; a real audit would add `@axe-core/playwright`.
- **No visual regression.**
- **The seeded realm's credentials are hard-coded** in `support/users.ts`. They belong to a
  development realm re-imported on every `docker compose up` and exist nowhere else.
- **Runs against `INSURANCE_AI_PROVIDER=fake`** in the default stack, so answer *text* is
  deterministic and not produced by a real model. The suite therefore asserts the grounding/
  citation *structure*, never specific model prose.

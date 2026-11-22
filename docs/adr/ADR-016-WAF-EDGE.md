# ADR-016: WAF as the Single Browser-Facing Edge

## Status

Accepted — FASE 20/21.

## Context

Across this program's successive mega-prompts, two slightly different topology diagrams were
given: one shows the WAF splitting directly to the frontend and the API Gateway in parallel
(`WAF -> {Frontend, Gateway}`); a later one shows `WAF -> Gateway -> {Frontend, Backend}`. Rather
than treat these as contradictory, this ADR records the reconciliation actually implemented and
why it satisfies the intent of both.

## Decision

**The WAF (`waf/`, `owasp/modsecurity-crs:nginx` + a custom path-routing server block) is the one
browser-facing origin.** It applies ModSecurity + OWASP Core Rule Set to every request, then routes
internally by path: `/api/**` to the gateway, everything else to the frontend's static Angular
bundle. From the browser's point of view there is exactly one address (the WAF); the gateway never
needs to know how to serve static files, and the frontend never needs to know about the gateway's
existence at build time (only `PUBLIC_API_BASE_URL`, now pointing at the WAF).

## Decisions

**1. `owasp/modsecurity-crs:nginx`, not a hand-rolled regex filter.** A real WAF engine (libmodsecurity3,
OWASP Core Rule Set - the same rule set commercial WAFs are frequently built on) is used verified
against real attack payloads, not simulated. See section "Evidence" below.

**2. Custom `default.conf.template` replaces the base image's single-backend one.** The base
image's own template proxies everything to one `${BACKEND}`; this deployment needs two
(`API_BACKEND`, `FRONTEND_BACKEND`), split by path. ModSecurity itself is **not** re-enabled inside
this custom template - the base image already turns it on globally via its own untouched
`conf.d/modsecurity.conf.template`. A first attempt that also added `modsecurity on;`/
`modsecurity_rules_file` inside the custom server block failed to start with a genuine
"duplicated rule id" error (the same 1858-rule CRS set loaded twice) - found and fixed empirically
by inspecting the real container's startup log, not assumed from documentation.

**3. `client_max_body_size 20m`** at the WAF - slightly more permissive than the gateway's own
15MB `RequestSize` filter (FASE 19), so the WAF's own limit is never the first one hit for a
legitimately-sized document upload; the gateway's tighter, per-route limit remains the actual
enforced ceiling.

**4. SQL injection and XSS protection remain the application's own responsibility - never
delegated to the WAF as the only defense.** Parameterized queries (JDBC/Spring Data) and Angular's
built-in template sanitization are what actually prevent these classes of vulnerability; the WAF
is a real, additional layer (defense in depth), not a substitute for secure application code. This
is stated explicitly because it is exactly the kind of distinction the brief's honesty rules
require ("SQL injection no debe depender de WAF").

**5. `/healthz` bypasses ModSecurity (`modsecurity off;`) and is unauthenticated.** The Docker
healthcheck needs a fast, reliable, unfiltered endpoint - identical reasoning to why
`/actuator/health/**` is public at the backend/gateway layer (FASE 17/19).

## Evidence

Verified against a standalone build (two plain nginx stand-ins for the frontend/gateway, so the
WAF's own behavior could be isolated from the rest of the stack) before wiring into
`docker-compose.yml`:

| Request | Result | Evidence |
|---|---|---|
| Normal GET to `/` | `200` | routed to the frontend stand-in |
| Normal GET to `/api/chat` | `404` | routed to the backend stand-in (proves path-based routing, not just "always 200") |
| `?id=1' OR '1'='1` | `403` | ModSecurity audit log: rule `942100` "SQL Injection Attack Detected via libinjection", anomaly score 5 |
| `?q=<script>alert(1)</script>` | `403` | rules `941100`/`941110`/`941160` (XSS via libinjection + script-tag + HTML-injection heuristics), anomaly score 15 |
| `User-Agent: sqlmap/1.6` | `403` | rule `913100` "Found User-Agent associated with security scanner" |
| `..%2f..%2f..%2fetc%2fpasswd` | `400` | rejected before reaching the backend |
| 25MB request body (limit 20MB) | `413` | `client_max_body_size` enforced |

Every blocked case above is backed by a real ModSecurity audit log entry (JSON, with exact rule
ID, matched data, and anomaly score) - not merely an observed HTTP status code, per the brief's
explicit "no basta con comprobar un HTTP 403... necesitamos evidencia del punto donde se bloqueó."

## Consequences

- `frontend`'s `PUBLIC_API_BASE_URL` now defaults to the WAF's published port (`:8000`), not the
  gateway's (`:8082`) - the gateway's own port remains published for direct developer access/
  debugging, same precedent as the backend's port 8080 (FASE 16 decision 6).
- `docker-compose.yml` gained a `waf` service depending on both `gateway` and `frontend` being
  healthy.
- What this WAF deliberately does **not** cover: TLS termination (plain HTTP throughout this PoC,
  matching Keycloak's own `sslRequired: none`), a commercial/managed WAF service's reputation
  feeds or bot-management heuristics, and API-schema-aware validation (OpenAPI-driven request
  validation remains the backend's job). None of these are claimed as implemented.

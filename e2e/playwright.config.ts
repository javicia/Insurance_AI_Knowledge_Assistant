import { defineConfig, devices } from '@playwright/test';

/**
 * FASE 26 (E2E). `baseURL` is the WAF (`http://localhost:8000`), never the gateway (`:8082`) or
 * the backend (`:8080`) directly: the WAF is the one real browser-facing edge in the docker-compose
 * topology (see docs/adr/ADR-016-WAF-EDGE.md), so an E2E suite that bypassed it would silently
 * stop covering ModSecurity/OWASP CRS, the security headers it adds, and the gateway's own
 * routing/rate-limiting - exactly the layers most likely to break a real browser session.
 *
 * No `webServer` block: this suite deliberately does NOT start the application itself. It runs
 * against the real `docker compose up -d` stack (ten services, real Keycloak, real PostgreSQL,
 * real Kafka), because standing up a mocked or partial stack would defeat the point. If the stack
 * is not running the suite fails fast in globalSetup with an explicit message rather than
 * producing confusing per-test failures.
 */
export default defineConfig({
  testDir: './tests',
  globalSetup: './global-setup.ts',
  // Real Keycloak login + a real RAG round trip are genuinely slow; these are not padded values,
  // they were raised only after observing real timings against the running stack.
  timeout: 90_000,
  expect: { timeout: 15_000 },
  // Serial. The gateway enforces per-identity rate limits (5/min on /api/evaluation, 10/min on
  // /api/documents - see RateLimitingFilter): parallel workers sharing the one seeded test user
  // would trip those limits and produce 429s that look like product bugs but are the suite
  // competing with itself.
  workers: 1,
  fullyParallel: false,
  // A retry masks flakiness rather than fixing it; this suite is expected to be deterministic.
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    // The WAF serves plain HTTP locally (TLS terminates at a real load balancer in a deployed
    // environment - documented limitation, see docs/security/WAF.md).
    ignoreHTTPSErrors: true,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});

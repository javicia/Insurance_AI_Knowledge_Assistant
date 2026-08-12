import { expect, test } from '@playwright/test';

import { fetchAccessToken } from '../support/auth';
import { ALICE_AI_USER } from '../support/users';

/**
 * The protections that only exist because traffic goes through the real edge (ModSecurity/OWASP
 * CRS at the WAF, then the gateway's own rate limiting and security headers). A suite that talked
 * to the backend directly would pass while every one of these was broken.
 */
test.describe('Edge security (WAF + gateway)', () => {
  test('security headers are present on the application shell', async ({ request }) => {
    const response = await request.get('/');
    const headers = response.headers();

    expect(headers['x-content-type-options']).toBe('nosniff');
    expect(headers['x-frame-options']).toBe('DENY');
    // The SPA shell is served by the frontend's nginx, which sets a referrer policy that still
    // permits same-origin referrers (needed for ordinary navigation) while never leaking the path
    // cross-origin. The API responses below are stricter - the two are set by different components
    // and are deliberately not asserted as one value.
    expect(['no-referrer', 'strict-origin-when-cross-origin', 'same-origin']).toContain(
      headers['referrer-policy'],
    );
    // Clickjacking protection must also be expressed in the CSP, not only the legacy header.
    expect(headers['content-security-policy']).toContain("frame-ancestors 'none'");
    expect(headers['content-security-policy']).toContain("script-src 'self'");
  });

  test('API responses carry the strict no-referrer policy', async ({ request }) => {
    const response = await request.get('/api/governance/ai-systems', { failOnStatusCode: false });

    expect(response.headers()['referrer-policy']).toBe('no-referrer');
    expect(response.headers()['x-content-type-options']).toBe('nosniff');
  });

  test('the CSP allows the OIDC issuer as a *prefix*, so the discovery document is reachable', async ({
    request,
  }) => {
    // Regression test for a real FASE 26 incident: the issuer was listed without a trailing
    // slash, which in CSP matches that one exact URL and nothing beneath it - so the browser
    // blocked .well-known/openid-configuration and login could never start. A path-prefix source
    // expression must end in "/".
    const csp = (await request.get('/')).headers()['content-security-policy'];
    const connectSrc = csp.split(';').find((directive) => directive.trim().startsWith('connect-src')) ?? '';
    const issuerSource = connectSrc.split(/\s+/).find((source) => source.includes('/realms/'));

    expect(issuerSource, 'connect-src must list the OIDC issuer').toBeDefined();
    expect(issuerSource!.endsWith('/'), `OIDC issuer source must end in "/" but was ${issuerSource}`).toBe(true);
  });

  test('the WAF blocks an SQL injection attempt', async ({ request }) => {
    const token = await fetchAccessToken(ALICE_AI_USER);

    const response = await request.get("/api/governance/ai-systems?id=1' OR '1'='1", {
      headers: { Authorization: `Bearer ${token}` },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(403);
  });

  test('the WAF blocks a cross-site scripting attempt', async ({ request }) => {
    const token = await fetchAccessToken(ALICE_AI_USER);

    const response = await request.get('/api/governance/ai-systems?q=<script>alert(1)</script>', {
      headers: { Authorization: `Bearer ${token}` },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(403);
  });

  test('the WAF blocks a known scanner user-agent', async ({ request }) => {
    const token = await fetchAccessToken(ALICE_AI_USER);

    const response = await request.get('/api/governance/ai-systems', {
      headers: { Authorization: `Bearer ${token}`, 'User-Agent': 'nikto/2.1.5' },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(403);
  });

  test('a traversal attempt in a query parameter is refused', async ({ request }) => {
    const token = await fetchAccessToken(ALICE_AI_USER);

    const response = await request.get('/api/governance/ai-systems?file=../../../../etc/passwd', {
      headers: { Authorization: `Bearer ${token}` },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(403);
  });

  test('per-identity rate limiting really throttles a burst and advertises Retry-After', async ({ request }) => {
    // /api/evaluation is the tightest limit (5/min per identity - RateLimitingFilter), chosen
    // here precisely so the test is short and unambiguous.
    const token = await fetchAccessToken(ALICE_AI_USER);
    const statuses: number[] = [];

    for (let attempt = 0; attempt < 8; attempt++) {
      const response = await request.get('/api/evaluation/runs/recent', {
        headers: { Authorization: `Bearer ${token}` },
        failOnStatusCode: false,
      });
      statuses.push(response.status());
      if (response.status() === 429) {
        expect(response.headers()['retry-after']).toBeDefined();
        expect(response.headers()['x-ratelimit-remaining']).toBe('0');
      }
    }

    expect(statuses).toContain(429);
  });

  test('an error response never leaks a stack trace or internal detail', async ({ request }) => {
    const response = await request.get('/api/this-endpoint-does-not-exist', { failOnStatusCode: false });
    const body = await response.text();

    expect(body).not.toMatch(/at (com|org|java)\./);
    expect(body).not.toMatch(/SQLException|jdbc:postgresql|BeanCreationException/i);
  });
});

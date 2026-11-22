import { expect, Page } from '@playwright/test';

import { TestUser } from './users';

/**
 * Performs the real OAuth2 Authorization Code + PKCE login the SPA uses - the browser is actually
 * redirected to Keycloak, a real HTML login form is filled, and Keycloak redirects back with a
 * real authorization code the app exchanges for a real token.
 *
 * Deliberately not short-circuited by injecting a token into localStorage: that would skip the
 * redirect/PKCE/callback handling which is exactly the part most likely to break, and would make
 * these tests pass against a build whose login flow is broken.
 */
export async function loginAs(page: Page, user: TestUser): Promise<void> {
  await page.goto('/');

  // authGuard redirects an unauthenticated browser straight to Keycloak.
  await page.waitForURL(/\/realms\/insurance-ai\/protocol\/openid-connect\/auth/, { timeout: 30_000 });

  await page.locator('#username').fill(user.username);
  await page.locator('#password').fill(user.password);
  await page.locator('#kc-login').click();

  // Back on the app's own origin, authenticated.
  await page.waitForURL((url) => !url.pathname.includes('/realms/'), { timeout: 30_000 });
  await expect(page.locator('body')).toBeVisible();
}

/**
 * Obtains a real access token via the dedicated `insurance-ai-e2e-test` confidential client
 * (direct access grant), for the API-level assertions that verify what the *backend* enforces
 * rather than what the UI renders. The browser-facing `insurance-ai-frontend` client deliberately
 * has direct access grants disabled, which is why a separate client exists for scripted testing -
 * see infra/keycloak/realm-export.json.
 */
export async function fetchAccessToken(user: TestUser, keycloakURL = 'http://localhost:8180'): Promise<string> {
  const response = await fetch(`${keycloakURL}/realms/insurance-ai/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'insurance-ai-e2e-test',
      client_secret: 'e2e-test-dev-secret-never-used-in-production',
      username: user.username,
      password: user.password,
    }),
  });

  if (!response.ok) {
    throw new Error(`Token request for ${user.username} failed with HTTP ${response.status}`);
  }
  const body = (await response.json()) as { access_token?: string };
  if (!body.access_token) {
    throw new Error(`Token response for ${user.username} contained no access_token`);
  }
  return body.access_token;
}

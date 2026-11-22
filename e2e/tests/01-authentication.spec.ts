import { expect, test } from '@playwright/test';

import { fetchAccessToken, loginAs } from '../support/auth';
import { ALICE_AI_USER } from '../support/users';

/**
 * Authentication through the real edge: WAF -> frontend/gateway -> Keycloak. Nothing here is
 * mocked; every redirect and token exchange actually happens.
 */
test.describe('Authentication', () => {
  test('an unauthenticated browser is redirected to the real Keycloak login page', async ({ page }) => {
    await page.goto('/assistant');

    await page.waitForURL(/\/realms\/insurance-ai\/protocol\/openid-connect\/auth/, { timeout: 30_000 });
    // A real Keycloak-rendered login form, not an app-rendered imitation.
    await expect(page.locator('#username')).toBeVisible();
    await expect(page.locator('#password')).toBeVisible();
  });

  test('invalid credentials are rejected by Keycloak and grant no session', async ({ page }) => {
    await page.goto('/assistant');
    await page.waitForURL(/\/realms\/insurance-ai\/protocol\/openid-connect\/auth/, { timeout: 30_000 });

    await page.locator('#username').fill(ALICE_AI_USER.username);
    await page.locator('#password').fill('definitely-the-wrong-password');
    await page.locator('#kc-login').click();

    // Still on Keycloak, with an error - never redirected back into the application.
    await expect(page).toHaveURL(/\/realms\/insurance-ai\//);
    await expect(page.locator('#input-error, .kc-feedback-text, [role="alert"]').first()).toBeVisible();
  });

  test('valid credentials complete the code+PKCE flow and land in the application', async ({ page }) => {
    await loginAs(page, ALICE_AI_USER);

    await expect(page).toHaveURL(/\/assistant/);
    await expect(page.getByRole('heading', { name: 'Insurance AI Assistant' })).toBeVisible();
  });

  test('the API refuses an anonymous call made through the WAF', async ({ request }) => {
    const response = await request.post('/api/chat', {
      data: { question: 'anything' },
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(401);
  });

  test('the API refuses a tampered bearer token', async ({ request }) => {
    const token = await fetchAccessToken(ALICE_AI_USER);

    const response = await request.post('/api/chat', {
      data: { question: 'anything' },
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}tampered` },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(401);
  });

  test('signing out clears the session so the app demands login again', async ({ page }) => {
    await loginAs(page, ALICE_AI_USER);

    await page.locator('.header__user-menu-trigger').click();
    await page.getByRole('menuitem', { name: /sign out/i }).click();

    // Back to Keycloak (either its logout confirmation or the login form) - in any case the app
    // must not keep rendering an authenticated session.
    await page.waitForURL(/\/realms\/insurance-ai\//, { timeout: 30_000 });
  });
});

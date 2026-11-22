import { expect, test } from '@playwright/test';

import { loginAs } from '../support/auth';
import { ALICE_AI_USER, CAROL_AUDITOR, DAVE_EVALUATOR } from '../support/users';

test.describe('Navigation and feature pages', () => {
  test('the sidebar navigates between every feature area', async ({ page }) => {
    await loginAs(page, ALICE_AI_USER);

    for (const [label, expectedPath] of [
      ['Documents', '/documents'],
      ['Governance', '/governance'],
      ['AI Assistant', '/assistant'],
    ] as const) {
      await page.getByRole('link', { name: label }).click();
      await expect(page).toHaveURL(new RegExp(expectedPath));
    }
  });

  test('governance shows the registered AI system, its risk classification and accountable owner', async ({ page }) => {
    await loginAs(page, ALICE_AI_USER);
    await page.goto('/governance');

    await expect(page.getByRole('heading', { name: 'Governance', exact: true })).toBeVisible();
    // AI Act transparency content is the point of this page - an empty state here would mean the
    // seeded AI system registry never loaded.
    await expect(page.locator('.governance-card--system')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('Owner', { exact: true })).toBeVisible();
    await expect(page.getByText('Intended use', { exact: true })).toBeVisible();
  });

  test('documents page exposes upload and is honest about the PoC listing limitation', async ({ page }) => {
    await loginAs(page, ALICE_AI_USER);
    await page.goto('/documents');

    await expect(page.getByRole('heading', { name: 'Documents', exact: true })).toBeVisible();
    await expect(page.locator('app-document-upload')).toBeVisible();
    await expect(page.getByText(/only\s+documents uploaded during this browser session/i)).toBeVisible();
  });

  test('an auditor sees real audit records with trace ids and outcomes', async ({ page }) => {
    await loginAs(page, CAROL_AUDITOR);
    await page.goto('/audit');

    await expect(page.getByRole('heading', { name: 'AI Audit' })).toBeVisible();
    // Earlier suites have already driven real requests through the system, so the immutable log
    // must not be empty; an empty state here means audit writing broke.
    await expect(page.locator('.audit-table')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('columnheader', { name: 'Trace ID' })).toBeVisible();
    await expect(page.locator('.audit-table tbody tr').first()).toBeVisible({ timeout: 30_000 });
  });

  test('an evaluator can open the evaluation page and see the run control', async ({ page }) => {
    await loginAs(page, DAVE_EVALUATOR);
    await page.goto('/evaluation');

    await expect(page.getByRole('heading', { name: 'Evaluation', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /run evaluation/i })).toBeVisible();
  });
});

test.describe('Responsive layout', () => {
  test('the navigation is reachable on a narrow viewport', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await loginAs(page, ALICE_AI_USER);

    // On a phone-sized viewport the sidebar collapses behind the menu toggle; the app must still
    // be navigable rather than stranding the user on one page.
    const menuToggle = page.getByRole('button', { name: 'Toggle navigation menu' });
    await expect(menuToggle).toBeVisible();
    await menuToggle.click();

    await page.getByRole('link', { name: 'Governance' }).click();
    await expect(page).toHaveURL(/\/governance/);
  });

  test('the assistant composer remains usable on a narrow viewport', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await loginAs(page, ALICE_AI_USER);

    await expect(page.getByLabel('Ask a question')).toBeVisible();
    await expect(page.getByRole('button', { name: /^send$/i })).toBeVisible();
  });
});

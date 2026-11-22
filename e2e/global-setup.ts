import { request } from '@playwright/test';

/**
 * Fails the whole run fast, with an actionable message, if the real stack is not up - otherwise
 * every individual test fails with an opaque connection error and the actual cause (nobody ran
 * `docker compose up -d`) is buried.
 */
async function assertReachable(url: string, what: string, expectedStatuses: number[]): Promise<void> {
  const context = await request.newContext({ ignoreHTTPSErrors: true });
  try {
    const response = await context.get(url, { timeout: 15_000 });
    if (!expectedStatuses.includes(response.status())) {
      throw new Error(
        `${what} answered HTTP ${response.status()} at ${url}, expected one of ${expectedStatuses.join('/')}.`,
      );
    }
  } catch (error) {
    throw new Error(
      `E2E preflight failed: ${what} is not reachable at ${url}.\n` +
        `These tests run against the real docker-compose stack - start it first:\n` +
        `    docker compose up -d\n` +
        `and wait until every healthcheck-bearing service reports healthy (docker compose ps).\n` +
        `Underlying error: ${(error as Error).message}`,
    );
  } finally {
    await context.dispose();
  }
}

export default async function globalSetup(): Promise<void> {
  const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:8000';
  const keycloakURL = process.env.E2E_KEYCLOAK_URL ?? 'http://localhost:8180';

  // The WAF's root serves the Angular bundle.
  await assertReachable(baseURL, 'The WAF edge', [200]);
  // Keycloak must be up for the real Authorization Code + PKCE login the UI performs.
  await assertReachable(
    `${keycloakURL}/realms/insurance-ai/.well-known/openid-configuration`,
    'Keycloak (realm insurance-ai)',
    [200],
  );
  // An unauthenticated API call must be refused - proves the gateway is routing and enforcing
  // authentication, not that some stale process is answering on the port.
  await assertReachable(`${baseURL}/api/governance/ai-systems`, 'The API through the WAF', [401]);
}

import { expect, test } from '@playwright/test';

import { fetchAccessToken } from '../support/auth';
import { ALICE_AI_USER, BOB_GOVERNANCE_ADMIN, CAROL_AUDITOR, DAVE_EVALUATOR } from '../support/users';

/**
 * The authorization matrix, asserted against the *backend* through the real WAF+gateway rather
 * than against what the UI chooses to render. The frontend's route guards are UX only (see
 * auth.guard.ts) - the security boundary is the backend, so that is what these tests pin down.
 *
 * Each case states the role, the endpoint, and the authority the endpoint requires, so a failure
 * says which privilege boundary moved.
 */
interface AuthorizationCase {
  readonly user: typeof ALICE_AI_USER;
  readonly label: string;
  readonly method: 'GET' | 'POST';
  readonly path: string;
  readonly body?: unknown;
  readonly expected: number;
  readonly because: string;
}

const CASES: readonly AuthorizationCase[] = [
  {
    user: ALICE_AI_USER,
    label: 'AI_USER',
    method: 'POST',
    path: '/api/chat',
    body: { question: 'What is covered?' },
    expected: 200,
    because: 'AI_USER has CHAT_READ',
  },
  {
    user: ALICE_AI_USER,
    label: 'AI_USER',
    method: 'GET',
    path: '/api/governance/ai-systems',
    expected: 200,
    because: 'AI_USER has GOVERNANCE_READ (AI Act Art. 50 transparency)',
  },
  {
    user: ALICE_AI_USER,
    label: 'AI_USER',
    method: 'GET',
    path: '/api/audit/recent',
    expected: 403,
    because: 'AI_USER has no AUDIT_READ',
  },
  {
    user: BOB_GOVERNANCE_ADMIN,
    label: 'AI_GOVERNANCE_ADMIN',
    method: 'GET',
    path: '/api/governance/ai-systems',
    expected: 200,
    because: 'AI_GOVERNANCE_ADMIN has GOVERNANCE_READ',
  },
  {
    user: BOB_GOVERNANCE_ADMIN,
    label: 'AI_GOVERNANCE_ADMIN',
    method: 'POST',
    path: '/api/chat',
    body: { question: 'What is covered?' },
    expected: 403,
    because: 'AI_GOVERNANCE_ADMIN deliberately has no CHAT_READ',
  },
  {
    user: BOB_GOVERNANCE_ADMIN,
    label: 'AI_GOVERNANCE_ADMIN',
    method: 'GET',
    path: '/api/audit/recent',
    expected: 403,
    because: 'no role implicitly grants audit access',
  },
  {
    user: CAROL_AUDITOR,
    label: 'AI_AUDITOR',
    method: 'GET',
    path: '/api/audit/recent',
    expected: 200,
    because: 'AI_AUDITOR has AUDIT_READ',
  },
  {
    user: CAROL_AUDITOR,
    label: 'AI_AUDITOR',
    method: 'GET',
    path: '/api/evaluation/runs/recent',
    expected: 403,
    because: 'AI_AUDITOR is scoped to audit only (least privilege)',
  },
  {
    user: DAVE_EVALUATOR,
    label: 'AI_EVALUATION_ADMIN',
    method: 'GET',
    path: '/api/evaluation/runs/recent',
    expected: 200,
    because: 'AI_EVALUATION_ADMIN has EVALUATION_READ',
  },
  {
    user: DAVE_EVALUATOR,
    label: 'AI_EVALUATION_ADMIN',
    method: 'GET',
    path: '/api/audit/recent',
    expected: 403,
    because: 'AI_EVALUATION_ADMIN is scoped to evaluation only',
  },
];

test.describe('Authorization matrix (enforced by the backend, through WAF + gateway)', () => {
  for (const authorizationCase of CASES) {
    test(`${authorizationCase.label} ${authorizationCase.method} ${authorizationCase.path} -> ${authorizationCase.expected} (${authorizationCase.because})`, async ({
      request,
    }) => {
      const token = await fetchAccessToken(authorizationCase.user);
      const options = {
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        failOnStatusCode: false,
        ...(authorizationCase.body ? { data: authorizationCase.body } : {}),
      };

      const response =
        authorizationCase.method === 'GET'
          ? await request.get(authorizationCase.path, options)
          : await request.post(authorizationCase.path, options);

      expect(response.status()).toBe(authorizationCase.expected);
    });
  }

  test('spoofed identity headers cannot escalate privilege', async ({ request }) => {
    const token = await fetchAccessToken(ALICE_AI_USER);

    // alice has no AUDIT_READ; claiming otherwise via headers must change nothing, because
    // authorities are derived from the validated JWT alone (JwtAuthoritiesConverter) and the
    // gateway strips these headers anyway (TrustedHeaderStrippingFilter).
    const response = await request.get('/api/audit/recent', {
      headers: {
        Authorization: `Bearer ${token}`,
        'X-User': 'carol.auditor',
        'X-Roles': 'AI_AUDITOR',
        'X-Authorities': 'AUDIT_READ',
      },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(403);
  });
});

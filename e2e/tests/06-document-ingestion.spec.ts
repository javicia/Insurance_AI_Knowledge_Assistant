import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { expect, test } from '@playwright/test';

import { fetchAccessToken } from '../support/auth';
import { ALICE_AI_USER, CAROL_AUDITOR } from '../support/users';

const FIXTURE = join(__dirname, '..', 'fixtures', 'e2e_test_policy.pdf');

/**
 * The asynchronous ingestion pipeline, end to end through the real edge:
 *
 *   WAF -> gateway -> backend -> PostgreSQL -> Kafka -> chunking -> embedding -> pgvector
 *
 * Nothing here is stubbed: a real PDF is uploaded, a real Kafka event carries it through real
 * consumers, and the resulting chunks become genuinely retrievable by the RAG pipeline.
 */
test.describe('Document ingestion', () => {
  test('a required parameter omitted is a 400, not a 500', async ({ request }) => {
    // Regression for a real FASE 25 finding: this answered `500 INTERNAL_ERROR` against the
    // running stack. A caller's malformed request must never be reported as a server fault.
    const token = await fetchAccessToken(ALICE_AI_USER);

    const response = await request.post('/api/documents', {
      headers: { Authorization: `Bearer ${token}` },
      multipart: {
        file: { name: 'policy.pdf', mimeType: 'application/pdf', buffer: readFileSync(FIXTURE) },
        type: 'POLICY',
        classification: 'INTERNAL',
        // `name` deliberately omitted
      },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(400);
    expect((await response.json()).code).toBe('MISSING_REQUEST_PARAMETER');
  });

  test('an unconvertible enum value is a 400 and does not echo the offending value', async ({ request }) => {
    const token = await fetchAccessToken(ALICE_AI_USER);

    const response = await request.post('/api/documents', {
      headers: { Authorization: `Bearer ${token}` },
      multipart: {
        file: { name: 'policy.pdf', mimeType: 'application/pdf', buffer: readFileSync(FIXTURE) },
        name: 'Invalid Enum Probe',
        type: 'NOT_A_REAL_DOCUMENT_TYPE',
        classification: 'INTERNAL',
      },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(400);
    const body = await response.text();
    expect(body).toContain('INVALID_REQUEST_PARAMETER');
    // Attacker-controlled input must never be reflected back.
    expect(body).not.toContain('NOT_A_REAL_DOCUMENT_TYPE');
  });

  test('a user without DOCUMENT_UPLOAD cannot upload', async ({ request }) => {
    const token = await fetchAccessToken(CAROL_AUDITOR);

    const response = await request.post('/api/documents', {
      headers: { Authorization: `Bearer ${token}` },
      multipart: {
        file: { name: 'policy.pdf', mimeType: 'application/pdf', buffer: readFileSync(FIXTURE) },
        name: 'Auditor Should Not Upload',
        type: 'POLICY',
        classification: 'INTERNAL',
      },
      failOnStatusCode: false,
    });

    expect(response.status()).toBe(403);
  });

  test('a real PDF is ingested asynchronously and reaches EMBEDDED', async ({ request }) => {
    const token = await fetchAccessToken(ALICE_AI_USER);
    // A unique name per run: the backend deduplicates by content hash, so reusing the fixture is
    // fine, but the assertion below must not be satisfied by a previous run's document.
    const uploadResponse = await request.post('/api/documents', {
      headers: { Authorization: `Bearer ${token}` },
      multipart: {
        file: { name: 'policy.pdf', mimeType: 'application/pdf', buffer: readFileSync(FIXTURE) },
        name: `E2E Ingestion ${Date.now()}`,
        type: 'POLICY',
        classification: 'INTERNAL',
      },
    });

    expect(uploadResponse.status()).toBe(201);
    const created = await uploadResponse.json();
    expect(created.id).toBeTruthy();
    expect(created.versions?.[0]?.id).toBeTruthy();

    // Ingestion is asynchronous (Kafka), so poll the real status endpoint rather than sleeping a
    // fixed amount and hoping.
    await expect
      .poll(
        async () => {
          const statusResponse = await request.get(`/api/documents/${created.id}`, {
            headers: { Authorization: `Bearer ${token}` },
            failOnStatusCode: false,
          });
          if (!statusResponse.ok()) return `HTTP ${statusResponse.status()}`;
          return (await statusResponse.json()).versions?.[0]?.status;
        },
        {
          message: 'the uploaded document must complete chunking + embedding',
          timeout: 60_000,
          intervals: [1_000, 2_000, 3_000],
        },
      )
      .toBe('EMBEDDED');
  });
});

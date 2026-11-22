/**
 * The four seeded realm users from infra/keycloak/realm-export.json, with the authorities each
 * realm role actually maps to in JwtAuthoritiesConverter (verified against that class, not
 * assumed). Kept in one place so an authorization test states *which* user it asserts about rather
 * than repeating credentials inline.
 *
 * These are development-only credentials for a local realm imported fresh on every
 * `docker compose up` - they are not secrets and the realm never exists outside a developer
 * machine (see docs/security/IAM_ARCHITECTURE.md).
 */
export interface TestUser {
  readonly username: string;
  readonly password: string;
  readonly realmRole: string;
  readonly authorities: readonly string[];
}

/** AI_USER -> CHAT_READ, DOCUMENT_UPLOAD, GOVERNANCE_READ. */
export const ALICE_AI_USER: TestUser = {
  username: 'alice.user',
  password: 'alice-password',
  realmRole: 'AI_USER',
  authorities: ['CHAT_READ', 'DOCUMENT_UPLOAD', 'GOVERNANCE_READ'],
};

/** AI_GOVERNANCE_ADMIN -> GOVERNANCE_READ, GOVERNANCE_WRITE (no chat, no audit, no evaluation). */
export const BOB_GOVERNANCE_ADMIN: TestUser = {
  username: 'bob.governance',
  password: 'bob-password',
  realmRole: 'AI_GOVERNANCE_ADMIN',
  authorities: ['GOVERNANCE_READ', 'GOVERNANCE_WRITE'],
};

/** AI_AUDITOR -> AUDIT_READ only (deliberately not governance or evaluation - least privilege). */
export const CAROL_AUDITOR: TestUser = {
  username: 'carol.auditor',
  password: 'carol-password',
  realmRole: 'AI_AUDITOR',
  authorities: ['AUDIT_READ'],
};

/** AI_EVALUATION_ADMIN -> EVALUATION_READ, EVALUATION_EXECUTE only. */
export const DAVE_EVALUATOR: TestUser = {
  username: 'dave.evaluator',
  password: 'dave-password',
  realmRole: 'AI_EVALUATION_ADMIN',
  authorities: ['EVALUATION_READ', 'EVALUATION_EXECUTE'],
};

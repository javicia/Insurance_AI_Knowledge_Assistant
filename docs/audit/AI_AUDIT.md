# AI Audit (FASE 9)

Status: living document. See `docs/governance/AI_GOVERNANCE.md` for the broader governance model,
`docs/security/SECURITY.md` for how guardrail events feed into this trail.

## 1. Purpose

Every `POST /api/chat` execution writes exactly one immutable `AuditRecord` (real PostgreSQL,
`ai_audit_records`, `V5__ai_governance.sql`) - regardless of whether it ends in a grounded answer,
a no-answer, a guardrail block, or an error. This is the system's execution log: which AI system,
which provider, which prompt version, what retrieval happened, and how it concluded - not a
content log of what was asked or answered.

## 2. What is recorded

`AuditRecord` (`domain.audit`) fields, written by `AuditService#record` (`application.audit`),
called from every exit path of `AskInsuranceKnowledgeUseCase.ask`:

| Field | Captures |
|---|---|
| `id`, `traceId`, `timestamp` | Identity and correlation - `traceId` is the same id that flows through logs and the API response |
| `aiSystemId` | Which AI System Registry entry this execution belongs to (`WellKnownAiSystems.INSURANCE_KNOWLEDGE_ASSISTANT`) |
| `provider` | Which LLM provider actually served this request (`openai`/`anthropic`/`fake`) |
| `promptKey`, `promptVersion` | Which Prompt Registry entry was active and used - `null` on paths that never reach prompt selection (blocked/no-answer) |
| `retrievalOutcome` | `RetrievalOutcome` (FASE 6) - semantic-only, lexical-only, hybrid, or none |
| `semanticCandidateCount`, `lexicalCandidateCount`, `finalCandidateCount` | Sizes of the retrieval funnel, not the candidates themselves |
| `groundingStatus` | The FASE 6 no-answer policy's verdict as a string |
| `promptInjectionDetected`, `piiDetectedInQuestion`, `piiDetectedInAnswer` | *That* a FASE 8 guardrail fired - never the matched text |
| `latencyMs` | Wall-clock duration of the full `ask()` call, measured via `System.nanoTime()` |
| `outcome` | `AuditOutcome`: `GROUNDED_ANSWER`, `NO_ANSWER`, `BLOCKED_BY_GUARDRAIL`, `ERROR` |
| `errorClassification` | Populated only on the `ERROR` path (exception class/category) |

## 3. What is deliberately NOT recorded (data minimization)

Consistent with FASE 8's stance (`docs/security/PII.md`) and brief section 18/26:

- No raw question text.
- No raw generated answer text.
- No retrieved chunk content.
- No individual per-chunk semantic/lexical/fusion/reranker scores - only funnel *counts*. A full
  per-chunk evaluation trace is FASE 10's job (AI Evaluation), not the audit trail's.
- No API keys, secrets, or tokens (never present in these fields to begin with).

This is enforced structurally, not just by convention: `AuditServiceTest
#recordNeverReceivesRawQuestionOrAnswerParameters` reflects over `AuditService#record`'s parameters
and fails the build if any `String` parameter name contains `question`, `answer`, or `content`.

## 4. Persistence

`ai_audit_records` (`V5__ai_governance.sql`): one row per execution, `id` primary key,
`ai_system_id` foreign key to `ai_systems`, indexes on `trace_id` (lookup) and `occurred_at DESC`
(recent-first listing). `JdbcAuditRepository` is plain JDBC, append-only (`save` is a plain
`INSERT`, never an upsert - unlike the other four governance repositories, an audit record is
never updated after creation).

Deliberately **not** included in `DatabaseCleanupExtension`'s per-test TRUNCATE list - see that
class's Javadoc: it is append-only log data with no cross-test collision risk (each test's records
carry their own generated `traceId`/`id`), so truncating it between tests would add no isolation
benefit.

## 5. REST API

`AuditController` (`/api/audit/**`, read-only - records are written internally, never via the API):

- `GET /api/audit/traces/{traceId}` - the single audit record for one execution, 404
  (`AuditRecordNotFoundException` -> 422 via `GlobalExceptionHandler`) if unknown.
- `GET /api/audit/recent?limit=20` - most recent records, `occurred_at DESC`.

## 6. Traceability

`traceId` is generated once per HTTP request and threaded through: the API response, application
logs, the `RagAnswer`, and the `AuditRecord` itself - so a support engineer can take a `traceId`
from a log line or an API response and look up exactly what happened for that request via
`GET /api/audit/traces/{traceId}`.

## 7. Tests

Unit: `AuditServiceTest` (record persists expected fields; structural no-raw-text-parameter guard).
Integration (real Postgres): `GovernanceIntegrationTest` - asking a real question through the full
pipeline writes a real, correctly-linked `AuditRecord`; a blocked (prompt-injection) question also
writes one, with `outcome = BLOCKED_BY_GUARDRAIL` and `promptInjectionDetected = true`.

## 8. Current limitations

- No token usage recorded (brief section 18 lists it as "when available"; neither the OpenAI nor
  Anthropic `LlmProvider` adapter currently surfaces usage metadata to the application layer - a
  future enhancement, not a FASE 9 scope item).
- No per-chunk score trace (see section 3) - only funnel counts. Full evaluation-grade retrieval
  traces belong to FASE 10 (AI Evaluation), which will have its own, separate dataset/results
  storage rather than overloading this operational audit log.
- No retention/archival policy - rows accumulate indefinitely in this PoC.
- No authentication on the read API (`docs/governance/AI_GOVERNANCE.md` section 7 - same PoC-wide
  limitation).
- `errorClassification` is a free-text string derived from the exception, not a fixed taxonomy -
  sufficient for a PoC's forensic lookup, not for automated error-rate dashboards.

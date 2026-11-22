# ADR-008: AI Governance Foundation (System/Model/Prompt Registries, Risk Assessment, Audit)

## Status

Accepted — FASE 9.

## Context

FASE 9 (AI Governance) needs AI System Registry, Model Registry, Prompt Registry, Risk Assessment,
Human Oversight, and AI Audit to be real, queryable, persisted bounded contexts (brief section
49) rather than Markdown-only claims. This is the largest single addition since FASE 6 and touches
`AskInsuranceKnowledgeUseCase` directly, so the boundaries need to be deliberate.

## Decisions

**1. Five contexts, one migration, one cohesive service each - not one class per CRUD operation.**
`AiSystemRegistryService`, `ModelRegistryService`, `PromptRegistryService`, `RiskAssessmentService`,
`AuditService` each own their aggregate's full lifecycle. Same reasoning as FASE 6's single
`HybridRetrievalService` orchestrator: five related one-method services would be indirection
without benefit for a PoC of this size.

**2. The Prompt Registry is load-bearing, not decorative.** `AskInsuranceKnowledgeUseCase` fetches
the active prompt via `PromptRepository#findActiveByKey` for every grounded answer; there is no
fallback to the old FASE 5 hardcoded `InsuranceRagSystemPrompt` constant at runtime. A missing
active prompt is a real `IllegalStateException`. The alternative (keep the registry as
metadata-only, keep using the hardcoded constant) would make the whole Prompt Registry a
governance-theater feature - exactly what brief section 49 (a "governance demo", but a *real* one)
warns against.

**3. "Only one ACTIVE prompt per key" is a `PromptRegistryService` invariant, not a `Prompt`
domain-entity invariant.** A single `Prompt` instance cannot see its siblings (no aggregate can see
across the repository by construction). `PromptRegistryService.activate(id)` retires any other
`ACTIVE` prompt with the same key first, inside the same use case. Rejected alternative: a database
partial unique index (`WHERE status = 'ACTIVE'`) - simpler to bypass by accident during Java-level
testing/seeding, and this project's tests already need to construct `Prompt` values freely; the
service-level guard was judged the better fit for a PoC that wants use-case tests, not just
constraint tests.

**4. Checksums are always recomputed, never trusted as input.** `Prompt`'s constructor computes
`checksum = sha256(content)` via `MessageDigest` internally; no caller can pass a checksum that
disagrees with the actual content. This makes the checksum meaningful for what it claims to prove
(this exact content, unmodified) rather than being one more field a caller could get wrong.
`PromptSeedDataTest` cross-checks the seeded migration's checksum against a freshly computed one
from `InsuranceRagSystemPrompt.TEXT`, so a divergence between the seed SQL and the historical
source text fails the build instead of silently drifting.

**5. `ModelRegistry` seeding happens at application startup, not migration time.** Unlike
`ai_systems`/`prompts`/`risk_assessments` (fixed PoC content, seeded once by `V5__ai_governance.sql`),
which provider/model is actually active depends on `insurance-ai.ai.provider`, a runtime
configuration value. A migration-time seed would be wrong for whichever provider isn't currently
selected. `ModelRegistrySeeder` (`@EventListener(ApplicationReadyEvent.class)`) registers the
currently-configured provider on every startup, checking first whether it is already registered
and active for this AI system, so restarts do not create duplicate rows.

**6. AI Audit is append-only and deliberately data-minimized.** `AuditRecord` has no field capable
of holding raw question/answer/chunk text - only identifiers, counts, and boolean flags (data
minimization, brief section 18/26, matching FASE 8's PII stance). This is enforced structurally by
`AuditServiceTest#recordNeverReceivesRawQuestionOrAnswerParameters`, a reflection-based test over
`AuditService#record`'s parameter names, not just by review discipline. `AuditRecord` is recorded
on every exit path of `AskInsuranceKnowledgeUseCase.ask` - blocked, no-answer, grounded, and error -
so the trail is complete regardless of outcome, not just for successful answers.

**7. `WellKnownAiSystems` is a single shared constant, not a duplicated literal.** Both
`AskInsuranceKnowledgeUseCase` and `ModelRegistrySeeder` need the same fixed `AiSystemId` and
prompt key seeded by `V5__ai_governance.sql`. A small `application.governance` holder class avoids
two independent hardcoded UUID literals silently drifting apart.

## Consequences

- `AskInsuranceKnowledgeUseCase`'s constructor grew to include `InputGuardService`,
  `PromptRepository`, and `AuditService` alongside its FASE 5/6 collaborators - it remains one
  orchestrating use case (matching the FASE 6/8 precedent of not splintering orchestration logic),
  now visibly the composition root for retrieval + security + governance + audit for a single
  request.
- Five new repositories (`AiSystemRepository`, `ModelRepository`, `PromptRepository`,
  `RiskAssessmentRepository`, `AuditRepository`) and five plain-JDBC adapters follow the same
  `RowMapper`-per-adapter pattern already used by `JdbcDocumentRepository` (FASE 3/4) - no new
  persistence technology introduced.
- `GovernanceIntegrationTest` (real Postgres) is the proof that this is not governance theater: the
  seeded AI system/prompt are genuinely queryable via the repositories, and asking a real question
  through the full pipeline writes a real, correctly-linked `AuditRecord` - including for a blocked
  (prompt-injection) request.
- `DatabaseCleanupExtension`'s TRUNCATE list deliberately excludes all five governance/audit
  tables - truncating `prompts` between tests would break the "every grounded answer needs an
  ACTIVE prompt" invariant for every *other* real-Postgres RAG test in the suite; truncating
  `ai_audit_records` would add no isolation benefit since it is append-only log data with no
  collision risk. See that class's Javadoc.

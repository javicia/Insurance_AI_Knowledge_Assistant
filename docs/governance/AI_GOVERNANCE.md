# AI Governance (FASE 9)

Status: living document. See `docs/adr/ADR-008-AI-GOVERNANCE-FOUNDATION.md` for the architectural
reasoning, `docs/governance/AI_ACT.md` for the regulatory-mapping honesty statement, `docs/governance/HUMAN_OVERSIGHT.md`
for the oversight model, `docs/audit/AI_AUDIT.md` for the audit trail.

## 1. Bounded contexts

Four registries, one execution log, all real persistence (PostgreSQL, `V5__ai_governance.sql`) -
not just documentation:

| Context | Aggregate | Package | Purpose |
|---|---|---|---|
| AI System Registry | `AiSystem` | `domain.aisystem` | What the system is for, who owns it, what it must never do, human oversight requirement |
| Model Registry | `AiModel` | `domain.model` | Which provider/model combinations are allowed (an allow-list) |
| Prompt Registry | `Prompt` | `domain.prompt` | Versioned, checksummed, auditable system prompt content |
| Risk Assessment | `RiskAssessment` | `domain.governance` | Documented rationale behind an AI system's risk classification |
| AI Audit | `AuditRecord` | `domain.audit` | Immutable per-request execution log |

Application services (one cohesive service per context, not one class per CRUD operation - see
`AiSystemRegistryService`'s Javadoc for why): `AiSystemRegistryService`, `ModelRegistryService`,
`PromptRegistryService`, `RiskAssessmentService`, `AuditService`.

REST API: `GovernanceController` (`/api/governance/**`), `AuditController` (`/api/audit/**`).

## 2. This project's one AI system (the governance demo)

Seeded by `V5__ai_governance.sql` (brief section 49):

- **Name**: Insurance Knowledge Assistant
- **Purpose**: Internal insurance knowledge assistance - answering employee questions about
  ingested insurance documentation, with citations, strictly grounded in retrieved content.
- **Prohibited use**: Automated insurance decision making - approving/rejecting claims,
  calculating premiums, determining eligibility or coverage, underwriting, or any automated
  decision about a specific person. **This system informs; a human always decides** (brief
  section 12).
- **Risk classification**: `LIMITED` (PoC self-assessment - see `docs/governance/AI_ACT.md`).
- **Human oversight**: required (see `docs/governance/HUMAN_OVERSIGHT.md`).

## 3. Prompt Registry is load-bearing, not decorative

`AskInsuranceKnowledgeUseCase` fetches the active prompt via `PromptRepository#findActiveByKey`
for every grounded answer - there is no fallback to the old FASE 5 hardcoded
`InsuranceRagSystemPrompt` constant at runtime (that class is kept only as the documented,
human-readable historical source of the seed migration's content - see its Javadoc and
`PromptSeedDataTest`). A missing active prompt is a real `IllegalStateException`, not silently
patched over.

`PromptRegistryService.activate` enforces "at most one `ACTIVE` prompt per key" by retiring any
previously-active prompt with the same key first - this is a use-case-level invariant, not a
`Prompt` domain-entity invariant (a single `Prompt` cannot see its siblings).

## 4. Model Registry seeding

Unlike `ai_systems`/`prompts`/`risk_assessments` (seeded once, at migration time, with fixed
content), `ai_models` is seeded at **application startup** (`ModelRegistrySeeder`, `infrastructure
.configuration`) because which provider is actually active depends on `insurance-ai.ai.provider` -
a migration-time value would be wrong for whichever provider isn't currently selected. Every
startup checks whether the currently-configured provider is already registered and active for
this AI system before registering it again.

## 5. Data minimization

Consistent with FASE 8's stance: none of these aggregates store secrets, API keys, or (for
`AuditRecord` specifically) raw question/answer/chunk text - see `docs/audit/AI_AUDIT.md`.

## 6. Tests

Unit: `AiSystemRegistryServiceTest`, `ModelRegistryServiceTest`, `PromptRegistryServiceTest`
(including the "only one active per key" invariant), `RiskAssessmentServiceTest`,
`AuditServiceTest`, `PromptSeedDataTest` (the seed migration's checksum matches
`InsuranceRagSystemPrompt.TEXT`'s actual SHA-256). Integration (real Postgres):
`GovernanceIntegrationTest` - the seeded AI system/prompt are genuinely queryable, and asking a
real question through the full pipeline writes a real, correctly-linked `AuditRecord`.

## 7. Current limitations

- No authentication/authorization on the governance/audit REST API - anyone who can reach
  `POST /api/chat` can also reach `/api/governance/**` (brief section 3/4: no IAM/OAuth2
  introduced in this PoC).
- `RiskAssessment`/model/prompt registration REST endpoints have no approval workflow beyond a
  single `approve`/`activate` call - no multi-party sign-off.
- No versioning/diffing UI for prompt content - only the REST API.

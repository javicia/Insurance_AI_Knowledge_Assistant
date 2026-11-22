# EU AI Act Mapping (FASE 9)

Status: living document. **This document does not constitute legal advice and does not assert
legal compliance.** It documents how this PoC's architecture demonstrates the technical
*capabilities* (classification, registration, documentation, traceability, transparency, human
oversight, risk management, logging, evaluation, change control) that AI Act-style governance
requires - see `docs/governance/AI_GOVERNANCE.md` for the implementation. Any real classification
or compliance determination **requires legal/compliance review**, explicitly out of scope for an
architectural PoC.

## 1. Primary source

Regulation (EU) 2024/1689 of the European Parliament and of the Council of 13 June 2024 laying
down harmonised rules on artificial intelligence (the "AI Act"), Official Journal, entered into
force 1 August 2024, with staged application through 2 August 2027 for most high-risk-system
obligations. Official text: [EUR-Lex, Regulation (EU) 2024/1689](https://eur-lex.europa.eu/eli/reg/2024/1689/oj/eng).
The article referenced by this project's author, [jgarciaperez.com/blog/ai-act-business-obligations](https://www.jgarciaperez.com/blog/ai-act-business-obligations),
is used only as contextual background for this project, **not as a legal source** - EUR-Lex is
the authority for any specific obligation.

## 2. The Act's four risk tiers (as documented publicly, subject to legal verification)

1. **Unacceptable risk** - prohibited practices (Article 5-style prohibitions: certain
   manipulative, exploitative, social-scoring, or untargeted biometric-scraping systems).
2. **High risk** - systems matching an Annex III use-case category (biometrics, critical
   infrastructure, education, employment, essential services, law enforcement, migration,
   democratic processes) or functioning as an Annex I safety component in a regulated product -
   subject to conformity assessment, risk management, human oversight and logging obligations.
3. **Limited risk** - primarily transparency obligations (e.g. disclosing that a user is
   interacting with an AI system, for chatbot-style systems).
4. **Minimal risk** - the majority of AI systems; no additional obligations beyond general law.

This project's `RiskClassification` enum (`domain.aisystem`) deliberately reuses this exact
four-value vocabulary (`MINIMAL`, `LIMITED`, `HIGH`, `UNACCEPTABLE`) because it is what
stakeholders reading a governance record will expect - **not because assigning one of these
values in this codebase is itself a legal act**.

## 3. This project's self-assessment (not a legal determination)

`V5__ai_governance.sql` seeds this project's one AI system with `risk_classification = 'LIMITED'`
and a `risk_assessments` row explaining why (see `docs/governance/AI_GOVERNANCE.md` section 2):
an internal-only knowledge assistant, no automated decision-making, no direct access to
individual customer records, chatbot-style interaction (matching the "limited risk" transparency
category's own paradigm case). This is:

- A **self-assessment**, performed by this project's own "AI Platform Team" persona, not an
  independent legal/compliance review.
- **Not a claim that Article-by-article obligations have been verified or satisfied.**
- Explicitly marked `status = 'DRAFT'` in the seed data, not `APPROVED` - see
  `RiskAssessmentService.approve`, which exists precisely so a real reviewer can formally approve
  (or reclassify) it later.

## 4. What this PoC demonstrates vs. what it does not

**Demonstrates (the point of FASE 8/9):**

- Classification (a recorded, revisable `RiskClassification`).
- Registration (`AiSystem`, `AiModel`, `Prompt` - all real, queryable, persisted records, not
  just documentation).
- Documentation (purpose, intended use, prohibited use, capabilities - `AiSystem`/`AiModel`
  fields).
- Traceability (`traceId` end to end, `AuditRecord` per execution - see `docs/audit/AI_AUDIT.md`).
- Transparency (the RAG system prompt itself states the assistant informs, does not decide -
  `InsuranceRagSystemPrompt`/Prompt Registry content; citations - `SourceReference`).
- Human oversight (an explicit, structured `HumanOversightRequirement` - see
  `docs/governance/HUMAN_OVERSIGHT.md`).
- Risk management (a documented `RiskAssessment` with rationale/controls/residual risk).
- Logging (`AuditRecord` persistence).
- Change control (`Prompt`/`AiModel` versioning and status transitions).

**Does not demonstrate, and this PoC makes no claim to:**

- A conformity assessment (required for genuinely high-risk systems under the Act).
- An independent/external legal review of the risk classification.
- A Fundamental Rights Impact Assessment.
- Technical documentation to the depth/format Annex IV would require for a high-risk system.
- Post-market monitoring infrastructure.
- Any determination of which specific AI Act articles/obligations apply to a real deployment of
  a system like this one - that determination is jurisdiction- and deployment-specific and
  requires qualified legal counsel.

## 4a. Article 50 transparency (FASE 14 audit remediation)

Article 50's obligation to disclose that a person is interacting with an AI system (rather than a
human) is aimed at natural-person-facing deployments where that would not otherwise be obvious.
This system's registered purpose (`AiSystem.intendedUse`) is internal employee tooling accessed
directly via its own API/Swagger UI - users are, by construction, aware they are calling an
internal API, not conversing with what might be mistaken for a human colleague. This PoC therefore
treats Article 50's disclosure obligation as not clearly triggered by this specific deployment
shape, while explicitly not asserting that as a legal conclusion for any other deployment context
this system might be adapted to (e.g. a consumer-facing chat widget would need this re-assessed).

## 5. Recommendation

Before any production use: commission a genuine legal/compliance review against the current text
of Regulation (EU) 2024/1689 (and any applicable national implementing legislation), using this
document's self-assessment only as a starting point, not a substitute.

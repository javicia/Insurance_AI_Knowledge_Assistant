# Human Oversight (FASE 9)

Status: living document. See `docs/governance/AI_GOVERNANCE.md` for the broader governance model.

## 1. The core principle

**This system informs; it never decides** (brief section 12, restated architecturally since
FASE 0). It has no code path that approves/rejects a claim, calculates a premium, determines
eligibility or coverage, or performs underwriting. A human is always responsible for any such
decision - the system's only role is to help that human find relevant documentation faster.

## 2. Where this is represented

`HumanOversightRequirement` (`domain.aisystem`) is a first-class, structured value object on
every `AiSystem`, not a footnote in a document:

```java
record HumanOversightRequirement(boolean required, String whenRequired, String escalationCondition,
        String decisionResponsibility)
```

Seeded for this project's one AI system (`V5__ai_governance.sql`):

- **required**: `true`
- **whenRequired**: "Any output used to inform a claim, coverage, eligibility, or underwriting
  decision"
- **escalationCondition**: "Any question or answer touching a specific customer's claim/coverage/
  eligibility outcome"
- **decisionResponsibility**: "The employee's manager or the claims team, per standard company
  escalation procedure"

This is queryable via `GET /api/governance/ai-systems/{id}` - not only documented in Markdown.

## 3. How this is reinforced elsewhere in the system

- **The system prompt itself** (Prompt Registry, `insurance-rag-system-prompt` v1): "You inform;
  you do not decide. Never state or imply a claims, pricing, eligibility or underwriting decision
  - a human is responsible for those decisions."
- **No-answer policy** (FASE 6): when evidence is insufficient, the system explicitly declines
  rather than guessing - never fills a gap with an invented answer that might look like a
  decision.
- **Citations** (FASE 5/6): every grounded answer traces back to a specific document/page/
  section, so a human reviewing the answer can verify it against the source themselves rather
  than trusting the system's output blindly.
- **Prohibited use** (`AiSystem.prohibitedUse`): "Automated insurance decision making... This
  system informs; a human always decides" - the same statement, recorded as governance data, not
  only prose.

## 4. What this PoC does not implement

- No workflow/ticketing integration that actually routes a flagged question to "the employee's
  manager or the claims team" - the escalation condition is documented, not automated (brief
  section 61: no overengineering for a PoC).
- No automated detection of "this question is actually about a specific customer's claim" beyond
  the FASE 8 PII guard's narrow pattern matching (which detects PII presence, not intent/context).
- No sign-off/acknowledgment workflow proving a human actually reviewed a given answer before
  acting on it.

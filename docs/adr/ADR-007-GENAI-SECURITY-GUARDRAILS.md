# ADR-007: GenAI Security Guardrails (Prompt Injection + PII)

## Status

Accepted — FASE 8.

## Context

FASE 8 (GenAI Security) needs a first, honest guardrail layer for prompt injection and PII,
without duplicating FASE 5's already-real structural defense (system/user message separation) or
pretending a PoC heuristic is production-grade ML.

## Decisions

**1. Both guards are ports (`PromptInjectionGuardPort`, `PiiGuardPort`), not plain application
classes.** Same reasoning as `RerankerPort` (`ADR-006` decision 4): the initial rule-based
implementations are explicitly meant to be replaced by a real classifier/managed service later,
without callers changing.

**2. Prompt injection in the *question* blocks the request; PII in the question does not.** A
question trying to override system instructions has no legitimate use in this internal
assistant. A question mentioning PII (e.g. a policy IBAN) can be entirely legitimate - this PoC's
threat model (brief section 12: the system never decides about people) does not require blocking
it, only ensuring it is not logged in plain text.

**3. Prompt injection detected in *retrieved document content* is logged, never blocks or drops
the chunk.** The actual defense there is FASE 5's structural system/user boundary
(`LlmMessageFormatter`), unchanged by this phase. Silently discarding a legitimate document
because of a heuristic pattern match would be a worse failure mode than the (already-neutralized)
risk it guards against.

**4. PII in the generated answer is logged, never redacted.** Redacting a grounded answer risks
corrupting legitimate citation content sourced from a retrieved document. This is a documented
PoC-level limitation (`docs/security/SECURITY.md` section 7), not an oversight.

## Consequences

- `AskInsuranceKnowledgeUseCase` gained a new collaborator (`InputGuardService`) but stayed a
  single orchestrating class rather than growing guard logic inline - consistent with FASE 6's
  "don't mix responsibilities" precedent.
- `RagAnswer.blocked()` reuses `GroundingStatus.NOT_GROUNDED` rather than adding a new status
  value - a blocked request is still, accurately, "no grounded answer was produced", just for a
  different reason than insufficient evidence.
- Both rule-based guards are honestly documented as PoC-level (`docs/security/PROMPT_INJECTION.md`,
  `docs/security/PII.md`) - brief section 9/60's explicit requirement.

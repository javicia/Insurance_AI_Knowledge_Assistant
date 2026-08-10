# GenAI Security (FASE 8)

Status: living document. Covers prompt injection and PII guardrails only - general application
security (IAM/OAuth2/authN/authZ) is explicitly out of scope for this PoC (brief section 3).

## 1. Threat model

This is an **internal insurance knowledge assistant** (brief section 12): it informs, it never
decides. The security controls in this phase address two GenAI-specific risks:

1. **Prompt injection** - text (in the user's question, or inside a retrieved document) trying
   to override system instructions or exfiltrate the system prompt.
2. **PII exposure** - personal data appearing in a question or a generated answer being logged/
   persisted more broadly than necessary.

## 2. Architecture

```
Question
   |
   v
InputGuardService.assessQuestion(question)     -- AskInsuranceKnowledgeUseCase
   |                    |
   v                    v
PromptInjectionGuardPort   PiiGuardPort
   |                    |
RuleBasedPromptInjectionGuard   RuleBasedPiiGuard      -- adapters.outbound.security
   |
   v
blocked()? --yes--> RagAnswer.blocked()   (no retrieval, no LLM call)
   |no
   v
... retrieval ...
   |
   v
InputGuardService.scanForInjection(chunk.content())    -- per retrieved chunk, log-only
   |
   v
LlmProvider.complete(...)
   |
   v
InputGuardService.scanForPii(completion.text())        -- output, log-only
```

Both `PromptInjectionGuardPort` and `PiiGuardPort` are ports (not plain classes) for the same
reason `RerankerPort` is (see `docs/adr/ADR-006-ADVANCED-RAG-RETRIEVAL-STRATEGY.md` decision 4):
the initial rule-based implementations are explicitly meant to be swappable for a real
classifier/managed service later, without `InputGuardService` or `AskInsuranceKnowledgeUseCase`
changing.

## 3. Honesty statement (brief section 9/60)

**`RuleBasedPromptInjectionGuard` and `RuleBasedPiiGuard` are PoC-level, regex/pattern-based
heuristics - not ML models, not equivalent to a production prompt-injection classifier or a
production PII scanner (e.g. Microsoft Presidio, AWS Comprehend).** They catch only the fixed
patterns documented in their Javadoc, are trivially bypassable by paraphrasing/encoding/
translation, and `RuleBasedPiiGuard` recognizes exactly four data types in Spanish national
formats (email, IBAN, Spanish DNI/NIE, Spanish phone numbers) - it is not a general PII scanner.
See `docs/security/PROMPT_INJECTION.md` and `docs/security/PII.md` for what each specifically
does and does not catch.

## 4. Structural defense (unchanged from FASE 5)

The actual defense against retrieved-document injection remains structural, not the guard: FASE
5's `LlmPrompt(systemInstructions, userQuestion, retrievedContextPassages)` keeps the system
prompt and retrieved content on permanently separate channels all the way to the vendor call
(`SystemMessage` vs. delimited `UserMessage` - see `LlmMessageFormatter`). A malicious phrase
inside a retrieved document is sent to the LLM, but only ever as data inside the delimited
untrusted-context section - it cannot reach the system-instruction channel. The FASE 8 guard adds
**detection and observability** on top of this (a WARN log when a retrieved chunk matches a known
pattern - see `SecurityGuardrailIntegrationTest`), it does not replace the structural boundary,
and it does not drop or alter flagged document content (a legitimate document should never be
silently mutilated because of a false-positive pattern match).

## 5. Configuration

`insurance-ai.security.prompt-injection.enabled` / `insurance-ai.security.pii.enabled` (both
default `true`, `application.yaml`). Disabling either skips that guard's scan entirely (not
"scan but ignore") - see `InputGuardService`.

## 6. Tests

Unit: `RuleBasedPromptInjectionGuardTest`, `RuleBasedPiiGuardTest`, `InputGuardServiceTest`.
Integration (real Postgres/Kafka): `SecurityGuardrailIntegrationTest` - a direct injection
question is blocked end to end without calling retrieval or the LLM; a document containing
injection phrasing is still retrieved, cited and answered normally.

## 7. Current limitations

- Detection is pattern-based only - no semantic/ML detection of paraphrased or obfuscated
  attacks.
- `RuleBasedPiiGuard` covers four Spanish-format data types only; no name/address detection.
- No output *redaction* on PII detection in the generated answer - only a WARN log
  (`AskInsuranceKnowledgeUseCase`). Redacting a grounded answer risks breaking legitimate
  citation content (e.g. a contact email that is genuinely part of a policy document).
- Guardrail events are logged, not yet persisted to an audit trail (FASE 9).

# ADR-012: FASE 14 Independent Audit Remediation

## Status

Accepted — FASE 14.

## Context

FASE 14 commissioned an independent, skeptical re-audit of the entire system (architecture, DDD,
Prompt Registry, AI Audit, security guardrails, RAG/retrieval/grounding, embeddings, LLM
providers, resilience, Kafka, database, Testcontainers, test quality, observability, API,
governance, AI Act wording, human oversight, documentation consistency), explicitly instructed not
to trust `FINAL_PROJECT_REPORT.md`'s own claims and to verify each one against the actual code. Full
findings are in `FINAL_ARCHITECTURE_AUDIT.md`. This ADR records the decisions behind the findings
that required a code change, not the findings that confirmed existing behavior was already correct.

## Decisions

**1. Close the two audit-recording gaps in `AskInsuranceKnowledgeUseCase.ask` (finding AU-01,
HIGH).** The active-prompt lookup and `buildSources` (both capable of throwing an unchecked
exception - a missing `ACTIVE` prompt, or a document/version deleted between retrieval and
citation-building) were not wrapped in the same catch-and-audit pattern already used around
retrieval and the LLM call. Fixed by wrapping both in one `try`/`catch (RuntimeException e)` block
that records `AuditOutcome.ERROR` before rethrowing - the same pattern, extended to close the last
gap, not a new pattern. Rejected alternative: a blanket `try` around the entire method body - would
have made the per-branch outcome/diagnostics fields recorded on success paths (grounding status,
candidate counts) harder to populate correctly for each specific exit point; the existing
per-branch structure was kept and simply widened.

**2. Add `insurance-ai.rag.lexical.min-rank` (finding RAG-01, HIGH).** The no-answer policy
treated any non-null `lexicalScore` as sufficient grounding evidence, reasoning that PostgreSQL's
`@@` operator "only ever returns genuine matches." True, but a single weak keyword overlap
satisfies `@@` exactly as readily as a strong multi-term match - `ts_rank_cd` was being computed by
`PostgresLexicalSearchAdapter` but never actually checked against a floor. Fixed by adding a
configurable minimum rank threshold, checked identically to the existing semantic threshold, in
`AskInsuranceKnowledgeUseCase.hasQualifyingCandidate`. **Defaults to `0.0`** - every historical
FASE 6-13 grounding decision is reproduced exactly (`0.0` accepts everything `@@` already accepted)
- so this is a mechanism addition, not a behavior change, until a deployment tunes it. Rejected
alternative: picking a specific non-zero default now - `ts_rank_cd`'s useful scale depends on a
corpus's own term-frequency/length distribution, which this PoC's synthetic fixture documents
cannot represent meaningfully; guessing a number would be exactly the kind of unearned precision
the project has consistently avoided elsewhere (RRF's `k=60`, reranking weights, evaluation
thresholds - all explicitly flagged "not scientifically calibrated" rather than presented as tuned).

**3. Reclassify HTTP 429/408 as transient in `OpenAiLlmAdapter`/`AnthropicLlmAdapter` (finding
LLM-01, HIGH).** FASE 11 classified every `HttpClientErrorException` (any 4xx) as
`PermanentProcessingException`, reasoning that Spring AI's own retry (`on-client-errors: false`)
never retries a 4xx internally, so anything reaching the adapter's catch block must already be
non-retry-safe. That reasoning conflated "Spring AI's *internal* loop shouldn't blindly retry 4xx"
with "no *caller-level* retry could ever help" - true for 400/401/403/404, false for 429 (rate
limit) and 408 (request timeout), both conventionally transient. Fixed via a new shared
`LlmFailureClassifier` (`adapters.shared.llm`, matching the existing shared-adapter-logic pattern
of `LlmMessageFormatter`) so both adapters classify identically and cannot drift.

**4. Add `RagAnswer.piiDetected` (finding SEC-06, MEDIUM).** `AskInsuranceKnowledgeUseCase`
already scanned the generated answer for PII and logged a `WARN` server-side (ADR-007's decision:
never redact, since redaction risks corrupting a legitimate citation). The audit confirmed that
decision is still sound - reversing it now would be a worse tradeoff, not a fix - but noted the API
caller had no way to know a returned answer might carry PII without reading server logs it likely
cannot access. Fixed by adding a `piiDetected` boolean to the response: pure transparency (the
answer text is byte-for-byte identical either way), not a new mitigation, and does not reopen
ADR-007's reasoning.

**5. Broaden `ArchitectureTest.domainMustBeFrameworkFree`'s blocklist (finding ARCH-01, LOW).**
The list predates FASE 11 (`io.micrometer`, added for observability) and never covered raw JDBC
(`java.sql`), both plausible-if-accidental domain-purity violations the rule could not have caught.
Domain was confirmed clean of both today (verified by direct grep, not just by this rule passing);
this is a coverage improvement, not a fix for an existing violation.

**6. New `V7__audit_indexes.sql`, never editing `V5` (minor DB finding).** `ai_audit_records` had
indexes on `trace_id`/`occurred_at` but not on the `ai_system_id` foreign key column. A new
migration, per this project's unbroken Flyway discipline of never modifying an already-applied
migration.

**7. Documentation-only fixes**: README's Production Gap Analysis now names `/swagger-ui.html`/
`/v3/api-docs` explicitly alongside `/api/governance/**`/`/api/audit/**`/`/actuator/**` (finding
DOC-02); `docs/governance/AI_ACT.md` gained a short section reasoning about Article 50's
inapplicability to this specific internal-tool deployment shape, without asserting that as a
general legal conclusion (finding TRANS-01); a weak assertion-free test in
`EmbeddingModelDescriptorTest` was strengthened (finding TQ-03).

## What was audited and found to already be correct (no change made)

Prompt Registry binding-ness and checksum integrity (PR-01/02/03), data-minimized `AuditRecord`
structure (AU-02), Document aggregate boundaries and rich (non-anemic) governance domain models
(DDD-01/02), RRF fusion correctness (RAG-02), reranker honesty (RAG-03), citation integrity
(RAG-04), embeddings-as-derived-projection (EMB-01), LLM provider switching wiring (LLM-02), Kafka
consumer idempotency (KAF-01 - a genuine, already-tested status-guard + replace-semantics +
unique-constraint design, not the naive gap the audit brief hypothesized), Kafka error handling
(KAF-02), migration hygiene (DB-01), Testcontainers reuse/isolation (TC-01), test-suite cleanliness
(TQ-01/02 - zero `@Disabled`, zero vacuous assertions, zero swallowed exceptions across all 67
files), test-configuration hygiene (CFG-01), log/secret hygiene (LOG-01-04), API DTO boundaries
(API-01/02), AI Act date accuracy and non-compliance-claiming wording (ACT-01), Human Oversight's
functional connection (GOV-01), the AI Governance invariants (GOV-02), the high-risk
decision-making boundary (HR-01 - confirmed no automated insurance decision-making capability
exists anywhere), and ADR sequence integrity (ADR-01). See `FINAL_ARCHITECTURE_AUDIT.md` for full
evidence per finding.

## Consequences

- `InsuranceAiProperties.Rag.Lexical` gained a `minRank` field - every test constructing this
  record directly was updated (4 files) to pass `0.0`, preserving prior behavior exactly.
- `RagAnswer` gained a `piiDetected` field - all 4 construction sites (2 in `RagAnswer` itself, 1
  in `AskInsuranceKnowledgeUseCase`, 1 in a test mock) were updated.
- `AskInsuranceKnowledgeUseCase`'s Javadoc was updated to describe both the widened audit coverage
  and the lexical minimum-rank criterion accurately.
- New regression tests: 6 in `AskInsuranceKnowledgeUseCaseTest` (2 for the audit-gap fix, 2 for the
  lexical-rank threshold, 2 for the `piiDetected` flag), 4 across `OpenAiLlmAdapterTest`/
  `AnthropicLlmAdapterTest` (429 and 408 now transient, 401 still permanent, regression-checked
  alongside the pre-existing generic-failure test).
- No test was weakened, no threshold was lowered in production configuration, no ArchUnit rule was
  relaxed - every change in this ADR either closes a real gap or adds a new, off-by-default
  mechanism.

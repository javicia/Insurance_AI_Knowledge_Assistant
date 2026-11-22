# ADR-004: Document Processing Failure Policy (FAILED, Retries, Idempotency)

## Status

Accepted — FASE 4.

## Context

FASE 3 modeled `DocumentStatus.FAILED` as a terminal state reachable from `PROCESSING` or
`PROCESSED`. FASE 4 introduces the real Kafka-driven pipeline
(`insurance.document.uploaded` -> extraction -> cleaning -> chunking -> persistence) and forces
a concrete question: what actually causes a `DocumentVersion` to become `FAILED`?

Not every failure is the same:

1. **Transient infrastructure errors** — Kafka broker briefly unreachable, PostgreSQL
   connection dropped, a momentary Tika/OS error, network blips. These say nothing about the
   document itself; retrying the identical operation shortly after very likely succeeds.
2. **Permanent processing errors** — a PDF Tika cannot parse at all, a file that is not
   actually a PDF, extraction that yields no usable text. No amount of retrying fixes these;
   the input itself is the problem.
3. **Content/business errors** — e.g. a future validation rule rejecting a document (out of
   scope for FASE 4, but the same category).
4. **Retries** — re-attempts of the *same* processing step, driven by messaging redelivery.
5. **Duplicate processing** — the *same* event, or the *same* document content, arriving more
   than once (Kafka's at-least-once delivery, or a user re-uploading identical bytes).

Conflating all of these into a single "processing failed, mark FAILED" rule would be wrong: it
would make the domain model punish transient infrastructure hiccups as if they were permanent
document defects, forcing a brand-new version/re-upload for something a retry would have fixed
seconds later.

## Decision

### FAILED stays terminal, but scoped to permanent failures only

`DocumentStatus.FAILED` is **not changed** from FASE 3 — it remains reachable only from
`PROCESSING`/`PROCESSED`, and once reached, no further transition is possible (see
`DocumentStatus.canTransitionTo`). What changes is the **policy** for when application code is
allowed to call `DocumentVersion.markFailed()`:

- **Transient infrastructure errors are never translated into `markFailed()`.** The Kafka
  consumer adapter (`adapters.inbound.kafka`) classifies exceptions raised while processing an
  `insurance.document.uploaded` event:
  - `TransientProcessingException` (extends `InfrastructureException`, brief section 52 —
    adapter-level technical failure): thrown by outbound adapters (PDF extraction I/O error,
    persistence connection error) for conditions expected to be retry-safe. The Kafka listener
    lets this propagate uncaught; Spring Kafka's `DefaultErrorHandler` with an
    `ExponentialBackOff` retries the same record a bounded number of times without committing
    the offset, then routes it to a dead-letter topic if retries are exhausted. **The
    `DocumentVersion`'s status is never touched during this retry loop** — it simply stays in
    whatever state it reached before the failure (typically still `PROCESSING`, since
    `startProcessing()` is called and persisted before extraction begins).
  - `PermanentProcessingException` (extends `InfrastructureException`): thrown by the
    extraction/chunking adapters when the content itself is unusable (unparseable PDF, empty
    extracted text). The consumer catches this specifically, calls
    `documentVersion.markFailed()`, persists it, and acknowledges the Kafka message (no
    retry — retrying would just fail again).
  - If the Kafka consumer's own retry budget is exhausted for what looked like a transient
    error, that is itself treated as effectively permanent for this attempt: the message is
    moved to a dead-letter topic (`insurance.document.uploaded.dlt`, Spring Kafka's default
    naming) rather than silently dropped, and **does not** call `markFailed()` automatically —
    an operator can inspect the dead-letter topic and decide whether to replay it (transient,
    now resolved) or re-upload (content genuinely bad). This preserves the property that
    `FAILED` in the domain always means "this content could not be processed", never "our
    infrastructure was briefly down".

### Retries are not a domain concept

`DocumentStatus` has no "attempt count", "retry", or "last error" field, and no transition
back to an earlier state. Retry/backoff bookkeeping is Kafka consumer machinery
(`adapters.inbound.kafka`), entirely outside the aggregate. This keeps `DocumentVersion` honest
about what it actually represents (business pipeline position) and keeps technical
retry-tuning (backoff intervals, max attempts) a pure infrastructure/configuration concern that
can change without touching the domain model or its tests.

### Idempotency is enforced at the application layer, not by relaxing domain invariants

`DocumentStatus.canTransitionTo` remains strict (no self-transitions, e.g. `PROCESSING ->
PROCESSING` is still invalid) — this is unchanged from FASE 3 and is not weakened. Instead:

- **Duplicate `insurance.document.uploaded` event for an already-handled version**: the
  consumer loads the `DocumentVersion` first and checks its `status()`. If it is no longer
  `UPLOADED`, the event is a duplicate delivery of something already processed (or in
  progress) — it is acknowledged and skipped without calling any domain transition method.
  This is the standard consumer-side idempotency check for at-least-once messaging.
- **Duplicate upload of identical content**: `RegisterDocumentUseCase` looks up
  `DocumentRepository.findByVersionContentHash(ContentHash)` before creating anything; if a
  version with that exact `ContentHash` already exists, the existing `Document`/version is
  returned and no new aggregate, event, or chunk is created. A unique database constraint on
  `document_versions.content_hash` (see `V2__documents.sql`) is the defensive backstop against
  races between the lookup and the insert (two concurrent uploads of the same bytes).
- **Duplicate chunk persistence** (the processing step runs more than once for the same
  version — e.g. a retried record after a partial failure): the chunk-persistence adapter
  replaces the full chunk set for a `DocumentVersionId` inside one transaction
  (delete-then-insert), so re-running the same processing step for the same version is
  naturally idempotent regardless of how many times it executes.

## Consequences

- Positive: the domain model approved in FASE 3 is unchanged — `DocumentStatus` still
  expresses exactly the business pipeline, nothing about Kafka or retries leaks into it.
- Positive: transient infrastructure problems self-heal via retry without ever creating a
  spurious `FAILED` version or forcing a needless re-upload.
- Positive: idempotency is verifiable independently at each layer (domain: status check;
  application: content-hash lookup; persistence: unique constraint + replace-semantics) —
  matches the multi-layered idempotency this ADR was asked to make explicit.
- Cost: the Kafka consumer adapter must correctly classify exceptions as transient vs.
  permanent (`TransientProcessingException` vs. `PermanentProcessingException`); getting this
  classification wrong in either direction either causes needless permanent failures or
  infinite silent retries. Mitigated by keeping the classification decision in exactly one
  place (the extraction/chunking/persistence adapters that throw these exceptions) rather than
  scattering `try/catch` heuristics through the consumer.

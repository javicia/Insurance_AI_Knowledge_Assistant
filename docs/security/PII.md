# PII Guard (FASE 8)

Status: living document. See `docs/security/SECURITY.md` for the overall architecture and
honesty statement - this document covers `RuleBasedPiiGuard` specifically.

## What it detects

Four regex-based patterns, all Spain-formatted (this PoC's demo documents are all
`country: ES` - brief section 9's own example data): `EMAIL` (generic), `IBAN` (Spanish `ES`
format, `ES\d{2}` + 20 digits), `NATIONAL_ID` (Spanish DNI - 8 digits + letter - or NIE - letter +
7 digits + letter), `PHONE` (Spanish 9-digit mobile/landline, optionally `+34`-prefixed).

## What it does not detect

Names, physical addresses, dates of birth, non-Spanish phone/ID formats, or any PII not matching
one of the four patterns above. Not a substitute for a production PII scanner (brief section
9/60).

## Data minimization by construction

`PiiGuard#scan` never returns the raw matched substring - only `PiiMatch(type, maskedValue)`,
where `maskedValue` is already redacted (e.g. `j***@example.com`, `ES**...1332`, a national ID
with every character but the last replaced by `*`). This applies even before any logging
decision is made: the detector's own output is safe to log/pass around by construction.

## Where it runs

1. **The question** (`InputGuardService.assessQuestion`): detected PII does **not** block the
   request (see `docs/security/SECURITY.md` section 1 - a question mentioning, say, a policy
   IBAN can be entirely legitimate for an internal assistant). It triggers an `INFO` log line
   with the question redacted via `PiiGuardPort#redact` (`[REDACTED:TYPE]` in place of each
   match) - data minimization applied to what actually reaches the logs.
2. **The generated answer** (`AskInsuranceKnowledgeUseCase.ask`, after `LlmProvider.complete`):
   detected PII logs a `WARN` (data present in the answer, worth a human reviewing why) - the
   answer itself is not redacted or blocked, since a grounded answer legitimately quoting a
   retrieved document (e.g. a contact email printed in a policy document) is not itself a
   violation; see `docs/security/SECURITY.md` section 7 for why output redaction was not
   implemented in this phase. **FASE 14 audit remediation**: since the answer text is genuinely
   unredacted, the API caller previously had no way to know this without reading server logs -
   `RagAnswer.piiDetected` now surfaces this same detection result in the response itself
   (transparency, not mitigation: the answer text is identical either way).

## Test evidence

`RuleBasedPiiGuardTest` (unit): each of the four types, confirmation that the raw value is never
returned, and that `redact` correctly replaces matches with a placeholder.
`InputGuardServiceTest` (unit): detected PII never blocks; the enabled/disabled configuration
toggle is honored (scan skipped entirely, not scanned-and-ignored, when disabled).

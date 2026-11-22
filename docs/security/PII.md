# PII Guard (FASE 8, extended FASE 24)

Status: living document. See `docs/security/SECURITY.md` for the overall architecture and
honesty statement - this document covers `RuleBasedPiiGuard` specifically.

## What it detects

Eight regex-based patterns:

| Type | Pattern | Notes |
|---|---|---|
| `EMAIL` | generic `local@domain.tld` | no format restriction beyond RFC-ish shape |
| `IBAN` | Spanish `ES\d{2}` + 20 digits | `ES`-country-code only, not other IBAN countries |
| `NATIONAL_ID` | Spanish DNI (8 digits + letter) or NIE (letter + 7 digits + letter) | Spanish format only |
| `PHONE` | Spanish 9-digit mobile/landline, optional `+34` | Spanish format only |
| `CREDIT_CARD` | 13-19 digits (optionally space/dash-grouped), **Luhn-validated** | see below |
| `API_KEY` | AWS Access Key ID (`AKIA...`), GitHub PAT (`ghp_...`), OpenAI-style (`sk-...`), Slack token (`xox[baprs]-...`) | four specific, recognizable shapes only - not a generic high-entropy-string detector |
| `JWT` | three dot-separated base64url segments starting with `eyJ` | matches the token shape, not its validity |
| `CREDENTIAL` | `password`/`passwd`/`pwd`/`secret`/`api_key`/`api-key`/`token` followed by `:`/`=` and a value | inline credential assignments in free text, case-insensitive |

All four original (FASE 8) patterns are Spain-formatted (this PoC's demo documents are all
`country: ES` - brief section 9's own example data). The four FASE 24 additions
(`CREDIT_CARD`/`API_KEY`/`JWT`/`CREDENTIAL`) are not "personal data" in the strict GDPR sense the
first four are - they are secrets that must never reach logs or persisted audit data either, and
share this detector/port rather than a separate one (brief FASE 24 explicitly groups them
together: "EMAIL, PHONE, IBAN, CREDIT CARD, DNI/NIE, API KEY, JWT, PASSWORD/SECRET").

### Why credit cards are Luhn-validated and the others are not

A bare 13-19 digit regex alone flags far too much (invoice numbers, phone extensions, document
IDs). Every `CREDIT_CARD` candidate is additionally checked against the Luhn checksum (ISO/IEC
7812 - the same algorithm every real issuer validates against) before being reported - this is a
genuine, evidence-based false-positive reduction, not decoration: `4111111111111111` (Luhn-valid,
the publicly-documented Visa test number) is flagged, `4111111111111112` (same length, invalid
checksum) is not, and `RuleBasedPiiGuardTest.doesNotFlagADigitRunThatFailsTheLuhnChecksum` asserts
this directly. Luhn validation proves a number is *shaped like* a real card number; it does not
prove the card is real, active, or currently valid - it is a false-positive filter, not a
card-validity check.

## What it does not detect

- Names, physical addresses, dates of birth.
- Non-Spanish phone/national-ID formats (a UK NI number, a US SSN, a French phone number all pass
  through undetected).
- IBANs from any country other than Spain.
- API keys/tokens in any shape other than the four listed (a generic 40-character hex secret with
  no recognizable prefix is invisible to this detector - a genuine limitation, not an oversight:
  a prefix-free "any long random string" pattern would have an unacceptable false-positive rate
  against ordinary hashes/IDs/UUIDs already present in legitimate text).
- Credit card numbers that happen to share exactly 12-18 leading digits with a longer non-card
  digit run embedded inside another already-detected pattern (e.g. the 20-digit BBAN portion of a
  Spanish IBAN can, in rare cases, itself pass the Luhn check and get double-tagged as
  `CREDIT_CARD` in addition to `IBAN` - a redundant duplicate match, not a data exposure, since
  masking still only reveals the same trailing digits either way).

Not a substitute for a production PII scanner (e.g. Microsoft Presidio, AWS Comprehend, Google
Cloud DLP) - brief section 9/60.

## Data minimization by construction

`PiiGuard#scan` never returns the raw matched substring - only `PiiMatch(type, maskedValue)`.
Masking strategy differs by category:

- **PII proper** (`EMAIL`/`IBAN`/`NATIONAL_ID`/`PHONE`/`CREDIT_CARD`): partially masked, showing
  just enough to be useful for a human reviewer to recognize *which* record is affected without
  reconstructing the full value (e.g. `j***@example.com`, `ES**...1332`, last 4 digits of a card).
- **Secrets** (`API_KEY`/`JWT`/`CREDENTIAL`): masked far more aggressively - at most the first 3
  characters are shown (enough to identify *that* it's an AWS key vs. a JWT vs. a password
  assignment), never a trailing portion. Unlike a phone number or card, even a few trailing
  characters of a real secret meaningfully narrow a brute-force/lookup search space for the rest,
  so the masking strategy is deliberately asymmetric between the two groups -
  `RuleBasedPiiGuardTest.secretTypesAreMaskedEntirelyNotJustPartially` asserts this.

This applies even before any logging decision is made: the detector's own output is safe to
log/pass around by construction.

## Input PII vs. output PII - the `piiDetected` semantics, made explicit

`RuleBasedPiiGuard`/`PiiGuardPort` itself has no notion of "input" or "output" - that distinction
lives entirely in `AskInsuranceKnowledgeUseCase.ask`, which calls `scan` twice, for two different
reasons, with two different consequences:

| | **Input PII** (the user's question) | **Output PII** (the LLM's generated answer) |
|---|---|---|
| When scanned | Before retrieval, in `InputGuardService.assessQuestion` | After `LlmProvider.complete`, on the final answer text |
| Blocks the request? | **No** - see `docs/security/SECURITY.md` section 1: a question mentioning, say, a policy IBAN can be entirely legitimate for an internal insurance assistant | **No** - a grounded answer legitimately quoting a retrieved document (e.g. a contact email printed in a policy document) is not itself a violation |
| What happens instead | `INFO` log line with the question **redacted** via `PiiGuardPort#redact` (`[REDACTED:TYPE]` in place of each match) | `WARN` log line (unredacted answer text is not itself logged - only that PII was found) |
| `SecurityEvent` (FASE 23) | `PII_DETECTED`, `reason="question"` | `PII_DETECTED`, `reason="answer"` |
| Surfaced to the API caller? | Not directly (only via the server-side log/security event) | **Yes** - `RagAnswer.piiDetected` (FASE 14 audit remediation: since the answer text is genuinely never redacted, the caller previously had no way to know this without reading server logs) |
| Is the underlying text redacted? | The **logged** copy of the question is redacted; the question itself is used unredacted for retrieval/the LLM call | **Never** - `RagAnswer.answer` is always the identical, unredacted text either way; `piiDetected` is **transparency, not mitigation** |

In short: `piiDetected` never means "this response was cleaned up" - it always means "this text was
found to contain a pattern this detector recognizes; nothing was removed or blocked because of
it." The one asymmetry between input and output is *where the detection result becomes visible*:
input PII stays server-side (log line, security event); output PII additionally surfaces in the
API response itself, because the caller is the one reading the LLM-generated text and has no other
way to know a match occurred.

## Test evidence

`RuleBasedPiiGuardTest` (unit, 13 tests): all eight types (including the Luhn-valid/invalid
credit-card pair, all four `API_KEY` shapes, a realistic three-segment JWT, and inline
`password`/`secret`/`api_key` assignments), confirmation the raw value is never returned for any
type, confirmation secrets are masked more aggressively than PII-proper, and that `redact`
correctly replaces every match (including the Luhn-gated credit-card path) with a placeholder.
`InputGuardServiceTest` (unit): detected PII never blocks; the enabled/disabled configuration
toggle is honored (scan skipped entirely, not scanned-and-ignored, when disabled).
`AskInsuranceKnowledgeUseCaseTest` (unit, FASE 23 additions): the real question-PII and
answer-PII call sites each emit the correctly-`reason`-tagged `SecurityEvent`, not just
`RuleBasedPiiGuard` tested in isolation - see `docs/security/SIEM.md` section 8.

## Known limitations (explicit, not implied)

This remains a **PoC-level, regex/pattern-based heuristic**, not an ML-based or production-grade
PII scanner. It is trivially bypassable by paraphrasing, encoding, splitting a secret across
multiple lines, or using a format outside the ones listed above. No confidence score, no context
awareness (e.g. it cannot distinguish "my password is hunter2" from "the word password appears in
this sentence about password policies" beyond the literal `[:=]` requirement), and no
locale-awareness beyond the hardcoded Spanish formats for the original four PII types.

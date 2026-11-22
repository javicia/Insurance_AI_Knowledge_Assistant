# ADR-011: API Documentation Strategy

## Status

Accepted — FASE 12.

## Context

By FASE 12 the REST API has grown to 5 controllers across 4 base paths (`/api/chat`,
`/api/documents`, `/api/governance`, `/api/audit`, `/api/evaluation`) with no OpenAPI
documentation at all - brief section 39's own guidance ("add OpenAPI docs once the API is mature
enough") is now satisfied. The API is also, at this point, the primary way a reader who is not
reading Java source verifies what this PoC actually exposes - so documentation needs to reflect
the real, current controllers, not an aspirational spec written ahead of the code.

## Decisions

**1. springdoc-openapi generated from the real controllers, not a hand-written OpenAPI YAML/JSON
file.** A hand-written spec drifts from the actual code the moment either changes independently -
exactly the kind of documentation/reality gap the rest of this project has consistently avoided
(e.g. `PromptSeedDataTest` checksum-verifies the seed migration against the real prompt constant
for the same reason). Generating from the controllers guarantees `/v3/api-docs` can never describe
an endpoint that does not exist.

**2. Version 3.x (`3.1.0`), not the more mature 2.x line.** springdoc 2.x is built for Spring Boot
3/Spring Framework 6; this project is on Spring Boot 4.1/Spring Framework 7 (with its restructured
module layout - `spring-boot-health`, `spring-boot-http-client`, `spring-boot-resttestclient`,
discovered while integrating this and FASE 11's actuator work). 3.1.0 was confirmed (by inspecting
its own POM's dependency versions) to target the Boot-4-era module names, and was verified to boot
cleanly and serve `/v3/api-docs`/`/swagger-ui.html` against this project's real context
(`OpenApiDocsIntegrationTest`).

**3. Operation-level (`@Operation`/`@Tag`) annotation, not field-level (`@Schema`) annotation on
every DTO.** Every summary/description added is one a reader could not already infer from the
endpoint's path, method and record component names alone (e.g. what a `200` with `NOT_GROUNDED`
means on `POST /api/chat`, or that a prompt's checksum is always server-recomputed) - the DTOs
themselves (well-named records already used as the Java types springdoc introspects) are left to
generate their schema automatically. Exhaustively annotating every field of every DTO across 5
controllers would be a large amount of low-value boilerplate for a PoC's documentation goal (brief
section 61).

**4. No API gateway, no separate documentation site/tooling.** `/swagger-ui.html` (bundled by
`springdoc-openapi-starter-webmvc-ui`) is the browsable documentation; no Redoc/Stoplight/external
hosting was introduced - consistent with this PoC's "no API Gateway" stance
(`docs/governance/AI_GOVERNANCE.md` section 7 and the Production Gap Analysis).

**5. `README.md`, `docs/architecture/C4.md` and `docs/architecture/COMPONENTS.md` were created in
this same phase, not deferred further.** `ARCHITECTURE.md` had referenced both since FASE 0 without
either existing; a reader arriving at the repository for the first time had no single entry point.
Written to describe the system as it actually stands after FASE 11 (13 phases in, not 1), per the
same "document reality, not aspiration" principle as decision 1.

## Consequences

- Every `@RestController` gained a class-level `@Tag` and per-endpoint `@Operation` - a small,
  additive change with zero behavioural effect (verified: full `./mvnw clean verify` unaffected
  besides the new tests below).
- `OpenApiConfiguration` (`infrastructure.configuration`) is the single place that owns the
  API-wide title/description/PoC disclaimer - not scattered across controllers.
- `OpenApiDocsIntegrationTest` (real Spring context, random port) is the proof this is not
  documentation theater: it asserts `/v3/api-docs` genuinely lists every controller's base path.
- `README.md` now exists as the project's front door; `docs/architecture/C4.md`/`COMPONENTS.md`
  fulfil `ARCHITECTURE.md`'s own forward references instead of leaving them permanently unresolved.

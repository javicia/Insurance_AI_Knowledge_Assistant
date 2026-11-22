# ADR-015: IAM via Keycloak + Spring Security OAuth2 Resource Server

## Status

Accepted — FASE 17.

## Context

Before this phase, no endpoint had any authentication or authorization - explicitly documented as
a Production Gap in every prior audit. FASE 17 closes that gap for the backend API. The brief
requires: no hand-rolled authentication, no manual JWT parsing, a real OIDC identity provider, and
per-endpoint fine-grained authorization mapped from four named roles.

## Decisions

**1. Keycloak, not a hand-rolled IdP.** A real, self-hosted OIDC/OAuth2 provider
(`quay.io/keycloak/keycloak:26.0`, `start-dev --import-realm`), declaratively configured via
`infra/keycloak/realm-export.json` (roles, clients, test users) rather than clicked together by
hand - reproducible, diffable, code-reviewable.

**2. Spring Security OAuth2 Resource Server, stateless.** The backend validates JWTs; it never
issues them, never stores a password, never implements signature verification itself
(`NimbusJwtDecoder` does that). No session, no cookie - every request must carry a fresh
`Authorization: Bearer` header, matching the API-only nature of the backend since ADR-014.

**3. `jwk-set-uri` (internal Docker network) and `issuer` claim validation (external,
browser-facing URL) are deliberately two different configuration values, not one `issuer-uri`.**
Spring Boot's auto-configured decoder assumes both are the same URL (via OIDC discovery); in this
topology they never are - see `docs/security/IAM_ARCHITECTURE.md` section 2 for the full
reasoning and the failure mode this avoids. `SecurityConfiguration#jwtDecoder` builds a custom
`NimbusJwtDecoder` with a `DelegatingOAuth2TokenValidator` combining the default validator set,
an explicit `JwtTimestampValidator`, and a `JwtClaimValidator<>("iss", ...)` checking against the
externally-visible issuer specifically.

**4. Seven fine-grained authorities, mapped from four realm roles by `JwtAuthoritiesConverter` -
one place, not scattered `@PreAuthorize` annotations across five controllers.** See
`docs/security/IAM_ARCHITECTURE.md` sections 3-4 for the full role→authority→endpoint table and
the reasoning behind each grant (in particular, why `AI_USER` also gets `GOVERNANCE_READ`: AI Act
Article 50 transparency).

**5. `SecurityErrorHandler` reuses the existing `ErrorResponse` contract, hand-builds its JSON
rather than autowiring `ObjectMapper`.** A real bug was found during implementation: `ObjectMapper`
is not reliably available as an autowireable bean at the point Spring Security eagerly constructs
`SecurityFilterChain` (`NoSuchBeanDefinitionException` at context startup, not merely a runtime
401/403 misbehavior). Rather than fight bean-creation ordering, the handler builds its three-field,
already-safe (no untrusted input) JSON body by hand - simpler and more robust than chasing a
correct `@DependsOn`/ordering fix for a serializer this class doesn't actually need.

**6. Three-layer test strategy, each isolating a different concern.** `JwtAuthoritiesConverterTest`
(pure unit) for the role→authority mapping; `SecurityAuthorizationIntegrationTest` (MockMvc +
spring-security-test's `jwt()` post-processor, no running IdP needed) for the full per-endpoint
enforcement matrix against the real `SecurityFilterChain`; `KeycloakJwtValidationTest`
(Testcontainers, a real Keycloak) for genuine signature/issuer trust - a tampered token and a
wrong-issuer check against the *same* validly-signed token are both proven rejected, not assumed.
See `docs/security/IAM_ARCHITECTURE.md` section 6.

**7. Existing REST-hitting tests updated to authenticate, not bypassed.** Adding Spring Security
changed `ApiRoutingIntegrationTest`'s three cases from unauthenticated `TestRestTemplate` calls
(now correctly `401`) to `MockMvc` + `jwt()` calls carrying every authority, so they keep testing
routing/MVC behavior specifically rather than becoming incidental security tests - authorization
itself has its own dedicated suite (decision 6). Every other pre-existing integration test
(`RagPipelineIntegrationTest`, `GovernanceIntegrationTest`, etc.) calls services/use cases directly
via `@Autowired`, never through HTTP, so none of them were affected by this phase at all -
confirmed by inspection before writing any code, not assumed.

## Consequences

- `domain` and `application` remain completely unaware Spring Security, JWTs, OAuth2, or Keycloak
  exist - all new code lives in `infrastructure.security`, verified by the unchanged
  `ArchitectureTest` rules (`applicationMustNotDependOnAdaptersOrInfrastructure`,
  `hexagonalLayersRespectDependencyDirection`).
- The backend test count changed net -3 from FASE 16's close (283) plus the new security suite: 3
  `ApiRoutingIntegrationTest` cases rewritten (not net-new), plus 7 (`JwtAuthoritiesConverterTest`)
  + 27 (`SecurityAuthorizationIntegrationTest`) + 3 (`KeycloakJwtValidationTest`, environment
  permitting - see that test's own timeout note) genuinely new cases.
- `docker-compose.yml` gained a `keycloak` service; `backend` now depends on it
  (`condition: service_healthy`) and receives two new environment variables
  (`INSURANCE_AI_OIDC_JWK_SET_URI`, `INSURANCE_AI_OIDC_ISSUER`).
- `docs/security/IAM_ARCHITECTURE.md` is the canonical reference for this phase's structure; this
  ADR records why, that document records what.

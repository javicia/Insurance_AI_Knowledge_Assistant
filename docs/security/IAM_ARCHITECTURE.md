# IAM Architecture (FASE 17)

Status: living document, describes the real implementation - `SecurityConfiguration`,
`JwtAuthoritiesConverter`, `SecurityErrorHandler` (`infrastructure.security`), the Keycloak realm
at `infra/keycloak/realm-export.json`, and the tests that verify all of it.

## 1. Why Keycloak, why OAuth2 Resource Server, not hand-rolled auth

The brief is explicit: no homemade authentication, no manual JWT parsing, no hand-rolled password
storage. Keycloak is a real, self-hosted OIDC/OAuth2 identity provider (not a mock); the backend is
a Spring Security **OAuth2 Resource Server** - it never issues tokens, never sees a password, and
never implements JWT signature verification itself (`NimbusJwtDecoder`, a Spring Security class,
does that). This is the same posture a real deployment would use, just with Keycloak running
locally instead of a managed identity provider.

## 2. The internal/external URL split - the one non-obvious piece of this design

In `docker-compose.yml`'s topology, a JWT's `iss` (issuer) claim always reflects the URL the
**browser** used to reach Keycloak (`http://localhost:8180/realms/insurance-ai`, the host-mapped
port), because that is genuinely where the token was issued from, from the caller's perspective.
The backend, however, fetches Keycloak's public signing keys over the **internal** Docker network
(`http://keycloak:8080/realms/insurance-ai/protocol/openid-connect/certs`) - faster, and doesn't
depend on the host port mapping existing at all.

Spring Boot's auto-configured `JwtDecoder` only supports the case where a single `issuer-uri`
property serves both purposes (via OIDC discovery) - that does not hold here. `SecurityConfiguration
#jwtDecoder` builds a custom `NimbusJwtDecoder` instead: `jwk-set-uri` (internal) for fetching
keys, and a separate `JwtClaimValidator<>("iss", ...)` (external, from
`insurance-ai.security.oauth2.issuer`) for validating the claim. Getting this wrong (pointing both
at the same URL) is the single most common Keycloak-in-Docker mistake - documented here so a future
change doesn't reintroduce it.

## 3. Roles and authorities

Keycloak realm roles (`infra/keycloak/realm-export.json`) are coarse identity/job-function labels;
the seven authorities below are what `SecurityConfiguration`'s per-endpoint rules actually check.
`JwtAuthoritiesConverter` is the one place this mapping is defined.

| Realm role | Authorities granted |
|---|---|
| `AI_USER` | `CHAT_READ`, `DOCUMENT_UPLOAD`, `GOVERNANCE_READ` |
| `AI_GOVERNANCE_ADMIN` | `GOVERNANCE_READ`, `GOVERNANCE_WRITE` |
| `AI_AUDITOR` | `AUDIT_READ` |
| `AI_EVALUATION_ADMIN` | `EVALUATION_READ`, `EVALUATION_EXECUTE` |

`AI_USER` also gets `GOVERNANCE_READ`: AI Act Article 50 transparency means any user of the system
should be able to see what it is (purpose, risk classification, human oversight requirements), not
just administrators. Every other role is scoped to exactly its own bounded context - least
privilege, no role implicitly grants audit or evaluation access.

## 4. Endpoint authorization matrix

| Endpoint | Method | Required authority |
|---|---|---|
| `/api/chat` | POST | `CHAT_READ` |
| `/api/documents` | POST | `DOCUMENT_UPLOAD` |
| `/api/documents/{id}` | GET | `DOCUMENT_UPLOAD` |
| `/api/governance/**` | GET | `GOVERNANCE_READ` |
| `/api/governance/**` | POST | `GOVERNANCE_WRITE` |
| `/api/audit/**` | GET | `AUDIT_READ` |
| `/api/evaluation/runs` | POST | `EVALUATION_EXECUTE` |
| `/api/evaluation/**` | GET | `EVALUATION_READ` |
| `/actuator/health/**` | GET | public (Docker healthcheck has no token) |
| `/v3/api-docs/**`, `/swagger-ui/**` | GET | public (API documentation, not data) |
| `/actuator/**` (everything else) | GET | `GOVERNANCE_READ` |
| any other path | any | authenticated (least-privilege default) |

Verified end-to-end by `SecurityAuthorizationIntegrationTest` (27 cases: anonymous/wrong-authority/
correct-authority for every row above, against the real `SecurityFilterChain` and real
controllers).

## 5. Error contract

401 (no/invalid/expired/wrong-issuer token) and 403 (valid token, missing authority) both return
the same `ErrorResponse` shape (`code`/`message`/`traceId`) every other API error uses
(`SecurityErrorHandler`), never Spring Security's default plain-text response. Neither response
reveals which specific validation step failed (bad signature vs. expired vs. wrong issuer all look
identical to the caller) - that detail exists only in the server-side log line, matching the
data-minimization practice `GlobalExceptionHandler` already established.

## 6. Test strategy - three layers, each proving something different

1. **`JwtAuthoritiesConverterTest`** (pure unit, no Spring context): the role→authority mapping
   table above, in isolation.
2. **`SecurityAuthorizationIntegrationTest`** (`@SpringBootTest` + MockMvc + spring-security-test's
   `jwt()` request post-processor): the full endpoint/authority enforcement matrix against the real
   `SecurityFilterChain` and real controllers - deliberately bypasses signature verification
   (`jwt()` injects a pre-built `Authentication` directly) so this suite runs fast and needs no
   running identity provider, isolating **authorization** coverage.
3. **`KeycloakJwtValidationTest`** (`@Testcontainers`, a real `KeycloakContainer`): proves the
   **authentication/trust** side - a genuinely valid token (real signature, real issuer, obtained
   from a real token endpoint) decodes successfully; a signature-tampered copy of that exact token
   is rejected; the same valid token checked against a deliberately wrong expected issuer is
   rejected. This is the one test that would catch a regression in the internal/external URL split
   from section 2.

## 7. Client design (Keycloak realm)

- `insurance-ai-frontend`: **public** client (no secret - an SPA cannot keep one), Authorization
  Code Flow with **PKCE required** (`pkce.code.challenge.method: S256`), no implicit flow, no
  direct access grants (a browser SPA should never see a user's password). See FASE 18's frontend
  OIDC integration.
- `insurance-ai-gateway`: **confidential** client (has a secret) with direct access grants enabled
  - used only for test/tooling purposes (`KeycloakJwtValidationTest` obtains a real token via
    password grant against this client, not the frontend client) - never used by the real browser
    flow.

## 8. Known limitations

- Development-only Keycloak realm/user credentials (`infra/keycloak/realm-export.json`) are
  committed in plaintext, exactly like `FakeLlmAdapter` - clearly a non-production PoC identity
  provider (`start-dev` mode, no TLS termination at Keycloak itself), never presented as
  production-hardened IAM. A real deployment would use a managed identity provider or a
  properly-secured Keycloak with secrets injected via a secret manager (see FASE 41).
- Gateway does not yet validate JWTs or propagate the principal - it currently only routes and
  applies CORS (FASE 16 scope). Full gateway-side JWT awareness is FASE 19.
- No refresh-token rotation policy tuning, no step-up authentication, no MFA - out of scope for
  this PoC's IAM story.
- `KeycloakJwtValidationTest`'s container startup was empirically measured to need up to 10 minutes
  on this project's development machine (Windows/Docker Desktop/WSL2, slow small-file I/O during
  Keycloak's own Quarkus build phase) - a real, evidence-based timeout, not an arbitrary one. See
  that test's own Javadoc for the measurement.

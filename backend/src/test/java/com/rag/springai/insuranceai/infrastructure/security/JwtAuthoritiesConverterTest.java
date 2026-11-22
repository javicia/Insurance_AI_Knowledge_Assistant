package com.rag.springai.insuranceai.infrastructure.security;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The role -&gt; authority matrix this system enforces (brief FASE 17 section 4/5), tested in
 * isolation from HTTP/Spring Security wiring - {@link SecurityAuthorizationIntegrationTest}
 * separately proves the {@link SecurityConfiguration} filter chain actually enforces whatever
 * authorities this converter produces.
 */
class JwtAuthoritiesConverterTest {

    private final JwtAuthoritiesConverter converter = new JwtAuthoritiesConverter();

    @Test
    void aiUserGetsChatDocumentAndGovernanceReadAuthorities() {
        assertAuthorities("AI_USER", "CHAT_READ", "DOCUMENT_UPLOAD", "GOVERNANCE_READ", "ROLE_AI_USER");
    }

    @Test
    void aiGovernanceAdminGetsBothGovernanceAuthorities() {
        assertAuthorities("AI_GOVERNANCE_ADMIN", "GOVERNANCE_READ", "GOVERNANCE_WRITE", "ROLE_AI_GOVERNANCE_ADMIN");
    }

    @Test
    void aiAuditorGetsOnlyAuditRead() {
        assertAuthorities("AI_AUDITOR", "AUDIT_READ", "ROLE_AI_AUDITOR");
    }

    @Test
    void aiEvaluationAdminGetsBothEvaluationAuthorities() {
        assertAuthorities("AI_EVALUATION_ADMIN", "EVALUATION_READ", "EVALUATION_EXECUTE", "ROLE_AI_EVALUATION_ADMIN");
    }

    @Test
    void aiAuditorNeverImplicitlyGetsGovernanceOrEvaluationAuthorities() {
        Collection<GrantedAuthority> authorities = convert(realmAccessJwt(List.of("AI_AUDITOR")));
        Set<String> names = names(authorities);

        assertTrue(names.contains("AUDIT_READ"));
        assertTrue(names.stream().noneMatch(n -> n.startsWith("GOVERNANCE_") || n.startsWith("EVALUATION_")
                || n.equals("CHAT_READ") || n.equals("DOCUMENT_UPLOAD")),
                "least privilege: an auditor must never gain any other bounded context's authority");
    }

    @Test
    void unknownKeycloakBuiltInRolesAreIgnoredNotErrored() {
        // Every real Keycloak realm carries built-in roles alongside the four this system
        // defines - offline_access, uma_authorization, default-roles-<realm>.
        Collection<GrantedAuthority> authorities = convert(
                realmAccessJwt(List.of("AI_USER", "offline_access", "uma_authorization", "default-roles-insurance-ai")));

        Set<String> names = names(authorities);
        assertTrue(names.contains("CHAT_READ"));
        assertTrue(names.contains("ROLE_offline_access"), "unknown roles still get a ROLE_ authority, just no mapped fine-grained one");
        assertTrue(names.stream().noneMatch(n -> n.equals("offline_access") || n.equals("uma_authorization")),
                "unknown roles must never be mistaken for a fine-grained authority name");
    }

    @Test
    void aJwtWithNoRealmAccessClaimYieldsNoAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "someone")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        assertTrue(converter.convert(jwt).isEmpty());
    }

    private void assertAuthorities(String realmRole, String... expected) {
        Collection<GrantedAuthority> authorities = convert(realmAccessJwt(List.of(realmRole)));
        assertEquals(Set.of(expected), names(authorities));
    }

    private Collection<GrantedAuthority> convert(Jwt jwt) {
        return converter.convert(jwt);
    }

    private Set<String> names(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private Jwt realmAccessJwt(List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "someone")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}

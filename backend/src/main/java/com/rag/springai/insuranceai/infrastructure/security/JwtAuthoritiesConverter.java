package com.rag.springai.insuranceai.infrastructure.security;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Maps Keycloak realm roles ({@code realm_access.roles} claim) to the fine-grained authorities
 * {@code SecurityConfiguration}'s per-endpoint rules actually check (brief FASE 17: "Diseña
 * autorización por endpoint" - four roles, seven authorities, not a 1:1 mapping). This is the one
 * place that mapping is defined; the endpoint rules never reference a realm role directly, so a
 * future role reshuffle only ever touches {@link #ROLE_AUTHORITIES}.
 *
 * <p>Every raw realm role is also kept as its own {@code ROLE_<name>} authority (e.g. {@code
 * ROLE_AI_USER}) - unused by any rule today, but a harmless, conventional Spring Security
 * addition that keeps the door open for a future coarse-grained {@code hasRole(...)} check
 * without another JWT-claim-mapping change.
 */
@Component
public class JwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** The four realm roles this system recognizes (see infra/keycloak/realm-export.json). */
    public enum RealmRole {
        AI_USER,
        AI_GOVERNANCE_ADMIN,
        AI_AUDITOR,
        AI_EVALUATION_ADMIN
    }

    /**
     * Role -&gt; authority mapping (brief FASE 17 section 4's authority list): {@code AI_USER} is
     * the employee-facing role (ask questions, upload documents) and also gets {@code
     * GOVERNANCE_READ} - AI Act Article 50 transparency means any user of the system should be
     * able to see what it is (purpose, risk classification, human oversight requirements), not
     * just administrators. {@code AI_GOVERNANCE_ADMIN} gets both governance authorities (an admin
     * can always do what a reader can). {@code AI_AUDITOR} and {@code AI_EVALUATION_ADMIN} are
     * each scoped to exactly their own bounded context - least privilege, no role implicitly
     * grants audit or evaluation access.
     */
    private static final Map<RealmRole, Set<String>> ROLE_AUTHORITIES = new EnumMap<>(RealmRole.class);

    static {
        ROLE_AUTHORITIES.put(RealmRole.AI_USER, Set.of("CHAT_READ", "DOCUMENT_UPLOAD", "GOVERNANCE_READ"));
        ROLE_AUTHORITIES.put(RealmRole.AI_GOVERNANCE_ADMIN, Set.of("GOVERNANCE_READ", "GOVERNANCE_WRITE"));
        ROLE_AUTHORITIES.put(RealmRole.AI_AUDITOR, Set.of("AUDIT_READ"));
        ROLE_AUTHORITIES.put(RealmRole.AI_EVALUATION_ADMIN, Set.of("EVALUATION_READ", "EVALUATION_EXECUTE"));
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return authorities;
        }
        Object rolesClaim = realmAccess.get("roles");
        if (!(rolesClaim instanceof List<?> roles)) {
            return authorities;
        }

        for (Object roleValue : roles) {
            String roleName = String.valueOf(roleValue);
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));
            RealmRole realmRole = parseKnownRole(roleName);
            if (realmRole != null) {
                for (String authority : ROLE_AUTHORITIES.get(realmRole)) {
                    authorities.add(new SimpleGrantedAuthority(authority));
                }
            }
        }
        return authorities;
    }

    private RealmRole parseKnownRole(String roleName) {
        try {
            return RealmRole.valueOf(roleName);
        }
        catch (IllegalArgumentException e) {
            // Keycloak realms always carry extra built-in roles (offline_access, uma_authorization,
            // default-roles-*) alongside the four this system defines - silently not mapping them
            // to an authority is correct, not a swallowed error.
            return null;
        }
    }
}

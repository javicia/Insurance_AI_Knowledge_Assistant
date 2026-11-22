package com.rag.springai.insuranceai.infrastructure.security;

import java.time.Duration;
import java.util.Map;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 17 (IAM): proves {@code SecurityConfiguration#jwtDecoder}'s trust logic against a real
 * Keycloak - not just hand-built {@link Jwt} objects (which {@code SecurityAuthorizationIntegrationTest}
 * uses for authorization-rule coverage, deliberately bypassing signature verification entirely).
 * A real token is obtained from a real token endpoint, decoded successfully; a signature-tampered
 * copy of that exact same token, and the same valid token checked against a deliberately wrong
 * expected issuer, are both genuinely rejected - "reject invalid tokens" is demonstrated, not
 * assumed.
 *
 * <p>Not a {@code @SpringBootTest}: this deliberately does not spin up Postgres/Kafka/the full
 * application context - it only needs the same {@code NimbusJwtDecoder}/validator construction
 * {@code SecurityConfiguration#jwtDecoder} uses, pointed at this test's own Keycloak container.
 */
@Testcontainers
class KeycloakJwtValidationTest {

    // Default Testcontainers wait strategy timeout (~1 min, via the library's own
    // HttpWaitStrategy default) was measured, not guessed, to be insufficient on this project's
    // development machine: a manual `docker run` of the identical image/command was observed
    // still in Keycloak's own Quarkus "build" phase (a real, live java process, gradually
    // increasing disk I/O, never crashed) after 7+ minutes, consistent with known slow
    // small-file I/O on Docker Desktop's Windows/WSL2 backend.
    //
    // FASE 23 re-measurement: the previous 10-minute value, run in isolation (no other Maven/
    // Testcontainers/Docker build process active) on this same machine, was itself exceeded - a
    // real log line proved the Quarkus augmentation phase alone took 594151ms (~9.9 min:
    // "Quarkus augmentation completed in 594151ms"), and the container was still running
    // Liquibase schema initialization when the 10-minute wait strategy gave up, killing the
    // log-follow connection ("Unexpected end of file from server") - total observed time to that
    // point: 685.8s (~11.4 min), i.e. genuinely past the old budget, not a fluke near the
    // boundary. 15 minutes restores real margin above this newer, larger measurement; if this
    // machine's Quarkus augmentation time keeps drifting upward across sessions, that drift - not
    // this timeout - is the thing to keep investigating (see docs/testing/TESTCONTAINERS.md).
    private static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
            .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/realm-export-test.json"),
                    "/opt/keycloak/data/import/realm-export-test.json")
            .withStartupTimeout(Duration.ofMinutes(15));

    @BeforeAll
    static void startKeycloak() {
        KEYCLOAK.start();
    }

    @AfterAll
    static void stopKeycloak() {
        KEYCLOAK.stop();
    }

    private String realIssuer() {
        // The library's own dedicated accessor, not manual string concatenation on
        // getAuthServerUrl() - that first attempt produced a malformed URL (missing separator
        // between the authority and path), since getAuthServerUrl() does not end in a trailing
        // slash the way it was assumed to.
        return KEYCLOAK.getIssuerUrl("insurance-ai-test");
    }

    private String jwkSetUri() {
        return realIssuer() + "/protocol/openid-connect/certs";
    }

    private JwtDecoder decoderExpectingIssuer(String expectedIssuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> withTimestamp = new JwtTimestampValidator();
        OAuth2TokenValidator<Jwt> withIssuer = new JwtClaimValidator<>("iss", expectedIssuer::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), withTimestamp,
                withIssuer));
        return decoder;
    }

    /**
     * Client Credentials grant (service account), not password grant - deliberately avoids the
     * brief's "no usar password grant salvo justificacion estrictamente necesaria" and, as a
     * side effect, sidesteps Keycloak's per-user required-actions machinery entirely (a service
     * account is not a user session at all), which is all this test actually needs: a genuinely
     * signed token from a real token endpoint, to validate signature/issuer trust - not a real
     * user login flow, which is already covered by the frontend's own OIDC integration (FASE 18).
     */
    private String obtainRealAccessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "test-client");
        form.add("client_secret", "test-client-secret");

        RestClient restClient = RestClient.create();
        Map<?, ?> response = restClient.post()
                .uri(realIssuer() + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        return (String) response.get("access_token");
    }

    @Test
    void aGenuinelyValidTokenFromTheRealIdentityProviderDecodesSuccessfully() {
        String realToken = obtainRealAccessToken();
        JwtDecoder decoder = decoderExpectingIssuer(realIssuer());

        Jwt decoded = assertDoesNotThrow(() -> decoder.decode(realToken));

        assertEquals(realIssuer(), decoded.getIssuer().toString(), "the real token's iss claim must be the realm's own issuer URL");
        assertTrue(decoded.getSubject() != null && !decoded.getSubject().isBlank(),
                "the real token must carry a subject claim identifying the client's service account");
    }

    @Test
    void aTokenWithATamperedSignatureIsRejected() {
        String realToken = obtainRealAccessToken();
        String[] parts = realToken.split("\\.");
        // Flip one character in the signature segment - the header/payload (and therefore the
        // claims an attacker might want to trust) are untouched, only the cryptographic proof is
        // corrupted.
        char[] signatureChars = parts[2].toCharArray();
        signatureChars[0] = signatureChars[0] == 'A' ? 'B' : 'A';
        String tamperedToken = parts[0] + "." + parts[1] + "." + new String(signatureChars);

        JwtDecoder decoder = decoderExpectingIssuer(realIssuer());

        assertThrows(JwtException.class, () -> decoder.decode(tamperedToken));
    }

    @Test
    void aGenuinelyValidTokenIsRejectedWhenTheConfiguredIssuerDoesNotMatch() {
        String realToken = obtainRealAccessToken();
        // Same real token, same real signing keys - only the *expected* issuer is wrong, exactly
        // the scenario InsuranceAiProperties.Security.OAuth2's Javadoc warns about if the
        // internal/external URL split were ever misconfigured.
        JwtDecoder decoder = decoderExpectingIssuer("http://wrong-issuer.example.com/realms/insurance-ai-test");

        assertThrows(JwtException.class, () -> decoder.decode(realToken));
    }
}

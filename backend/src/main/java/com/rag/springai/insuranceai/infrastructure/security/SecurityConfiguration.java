package com.rag.springai.insuranceai.infrastructure.security;

import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * FASE 17 (IAM): Spring Security OAuth2 Resource Server, never hand-rolled JWT parsing or
 * authentication. Stateless (no session, no cookie) - every request must carry a valid
 * {@code Authorization: Bearer <jwt>} header issued by Keycloak. Lives entirely in
 * {@code infrastructure} (never {@code domain}/{@code application}, both of which remain unaware
 * Spring Security, JWTs, or Keycloak exist - {@code ArchitectureTest} enforces this mechanically).
 *
 * <p><b>Per-endpoint authorization (brief FASE 17 section 4)</b> is declared entirely in
 * {@link #securityFilterChain}, one place, rather than scattered {@code @PreAuthorize}
 * annotations across five different controllers - see the endpoint/authority table in
 * {@code docs/security/IAM_ARCHITECTURE.md}. {@link JwtAuthoritiesConverter} is the one place the
 * four Keycloak realm roles are mapped down to these seven fine-grained authorities.
 */
@Configuration
public class SecurityConfiguration {

    private final JwtAuthoritiesConverter jwtAuthoritiesConverter;
    private final SecurityErrorHandler securityErrorHandler;

    public SecurityConfiguration(JwtAuthoritiesConverter jwtAuthoritiesConverter,
            SecurityErrorHandler securityErrorHandler) {
        this.jwtAuthoritiesConverter = jwtAuthoritiesConverter;
        this.securityErrorHandler = securityErrorHandler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(jwtAuthoritiesConverter);

        http
                // Stateless bearer-token API: no browser form login, no CSRF-vulnerable cookie
                // session to protect - see docs/security/IAM_ARCHITECTURE.md for the reasoning.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // The Docker healthcheck (docker-compose.yml) curls this with no
                        // Authorization header - it must stay public, or the container can never
                        // report healthy and every dependent service's `depends_on: condition:
                        // service_healthy` would block forever.
                        .requestMatchers("/actuator/health/**").permitAll()
                        // API documentation itself is not sensitive data - standard practice,
                        // and required for Swagger UI's "Authorize" button to even load the
                        // OpenAPI spec before a caller has a token yet.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Every other actuator endpoint (env, metrics, beans, ...) is genuinely
                        // sensitive operational data - restricted to the closest role this PoC
                        // has to an operator (brief: "Actuator debe tener politica propia").
                        .requestMatchers("/actuator/**").hasAuthority("GOVERNANCE_READ")

                        .requestMatchers(HttpMethod.POST, "/api/chat").hasAuthority("CHAT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/documents").hasAuthority("DOCUMENT_UPLOAD")
                        .requestMatchers(HttpMethod.GET, "/api/documents/**").hasAuthority("DOCUMENT_UPLOAD")
                        .requestMatchers(HttpMethod.GET, "/api/governance/**").hasAuthority("GOVERNANCE_READ")
                        .requestMatchers(HttpMethod.POST, "/api/governance/**").hasAuthority("GOVERNANCE_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/audit/**").hasAuthority("AUDIT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/evaluation/runs").hasAuthority("EVALUATION_EXECUTE")
                        .requestMatchers(HttpMethod.GET, "/api/evaluation/**").hasAuthority("EVALUATION_READ")

                        // Least-privilege default: anything not explicitly permitted above still
                        // requires a valid token, even if no specific authority rule matched it.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(securityErrorHandler))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(securityErrorHandler));

        return http.build();
    }

    /**
     * A custom {@link JwtDecoder}, not the auto-configured default, because {@code jwk-set-uri}
     * (where signing keys are fetched from - the internal Docker network address) and the
     * expected {@code issuer} claim (the browser-facing address actually printed into every
     * token Keycloak issues) are two different URLs in this topology - see
     * {@link InsuranceAiProperties.Security.OAuth2}'s Javadoc for the full explanation. Spring
     * Boot's auto-configured decoder only supports the case where a single {@code issuer-uri}
     * property serves both purposes (via OIDC discovery), which does not hold here.
     */
    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties oauth2Properties, InsuranceAiProperties insuranceAiProperties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(oauth2Properties.getJwt().getJwkSetUri()).build();

        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> withTimestamp = new JwtTimestampValidator();
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> withIssuer = new JwtClaimValidator<>("iss",
                issuer -> insuranceAiProperties.security().oauth2().issuer().equals(issuer));
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), withTimestamp, withIssuer));

        return decoder;
    }
}

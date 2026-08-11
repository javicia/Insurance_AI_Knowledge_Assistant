package com.rag.springai.insuranceai.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * FASE 19: the gateway independently validates JWTs too - defense in depth (brief: "validacion
 * JWT si corresponde arquitectonicamente"). A request the gateway itself already knows carries no
 * valid token never reaches the backend at all; the backend (FASE 17) still independently
 * re-validates every request it does receive and applies the real fine-grained per-endpoint
 * authorization - the gateway's check here is coarse (authenticated or not) by design, not a
 * duplicate of the backend's authority matrix.
 *
 * <p>Reuses the same internal-jwk-set-uri/external-issuer split as
 * {@code SecurityConfiguration#jwtDecoder} in the backend - see
 * {@code docs/security/IAM_ARCHITECTURE.md} section 2 for the full reasoning.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfiguration {

    @Value("${insurance-ai.security.oauth2.issuer}")
    private String issuer;

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // The Docker healthcheck curls this with no Authorization header.
                        .pathMatchers("/actuator/health/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtDecoder(jwtDecoder)))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(OAuth2ResourceServerProperties oauth2Properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(oauth2Properties.getJwt()
                .getJwkSetUri()).build();

        OAuth2TokenValidator<Jwt> withTimestamp = new JwtTimestampValidator();
        OAuth2TokenValidator<Jwt> withIssuer = new JwtClaimValidator<>("iss", issuer::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), withTimestamp,
                withIssuer));

        return decoder;
    }
}

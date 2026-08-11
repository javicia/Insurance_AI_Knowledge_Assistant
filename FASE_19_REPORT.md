# FASE 19 — API Gateway Hardening

**Fecha**: 2026-08-11
**Estado**: Cerrada. Gateway como edge boundary real, verificado con tests propios.

## 1. Qué se ha implementado

1. **Validación JWT en el gateway** (`GatewaySecurityConfiguration`, `spring-boot-starter-oauth2-
   resource-server` reactivo): defensa en profundidad - una petición sin token válido nunca llega
   siquiera al backend. Reutiliza el mismo patrón de desacople `jwk-set-uri` (interno)/`issuer`
   (externo) que el backend (FASE 17). Autorización gruesa (autenticado o no) - el backend sigue
   siendo el único que aplica la matriz fina de autoridades por endpoint.
2. **`TrustedHeaderStrippingFilter`**: elimina incondicionalmente `X-User`, `X-Roles`,
   `X-Principal`, `X-Authenticated-User` de toda petición entrante, con la precedencia más alta
   posible (antes que cualquier otro filtro) - un cliente no puede falsificar identidad vía
   headers, aunque hoy nada aguas abajo confíe en ellos (defensa estructural, no reactiva a un
   hallazgo).
3. **Rutas explícitas con método HTTP restringido** (`Method=POST` para chat,
   `Method=GET,POST` para documents/governance/evaluation, `Method=GET` para audit) - ninguna
   ruta catch-all, cada una limitada exactamente a lo que el controlador real del backend soporta.
4. **Límite de tamaño de petición** (`RequestSize` filter, 15MB) en las 5 rutas.
5. **Timeouts** (`connect-timeout: 5000ms`, `response-timeout: 30s`) hacia el backend.
6. `docker-compose.yml`: variables `INSURANCE_AI_OIDC_JWK_SET_URI`/`INSURANCE_AI_OIDC_ISSUER`
   añadidas al servicio `gateway`.

## 2. Archivos principales

`gateway/src/main/java/.../gateway/{GatewaySecurityConfiguration,
TrustedHeaderStrippingFilter}.java`, `gateway/src/main/resources/application.yaml` (reescrito),
`gateway/pom.xml` (+2 dependencias), `docker-compose.yml`.

## 3. Decisiones arquitectónicas

- **El gateway NO propaga un header de identidad propio** - decisión deliberada, no una omisión.
  El JWT original ya es el mecanismo de propagación del principal end-to-end (Spring Cloud Gateway
  reenvía headers por defecto); introducir un header adicional de confianza (`X-Authenticated-
  User`, etc.) duplicaría esa función y añadiría una nueva superficie potencialmente falsificable
  si el gateway se configurase mal en el futuro - exactamente el patrón que
  `TrustedHeaderStrippingFilter` existe para neutralizar. El backend sigue validando el JWT real de
  forma completamente independiente (zero-trust, no "confío en lo que dice el gateway").
- **Autorización del gateway es gruesa (autenticado/no autenticado), no duplica la matriz fina del
  backend** - evita mantener la misma tabla de autoridades en dos sitios que podrían divergir.
- **`TrustedHeaderStrippingFilter` con la precedencia más alta posible, antes que
  `CorrelationIdGlobalFilter`** - un header falsificado no debe estar presente ni siquiera
  transitoriamente para ningún filtro posterior.

## 4. Seguridad

Ver puntos 1-4 de la sección 1. El gateway es ahora un verdadero *edge boundary*: valida
autenticación, sanitiza headers de identidad falsificables, limita tamaño y tiempo de petición, y
restringe métodos HTTP por ruta - no un simple proxy transparente.

## 5. Tests - resultado real

`./mvnw clean verify` (módulo `gateway`, independiente): **4/4 PASS**
(`CorrelationIdGlobalFilterTest` x2, `GatewayApplicationTests` x1 - contexto real cargando la
configuración de seguridad reactiva sin necesitar Keycloak en ejecución, gracias a que
`NimbusReactiveJwtDecoder` es perezoso -, `TrustedHeaderStrippingFilterTest` x1 - prueba las 4
cabeceras prohibidas eliminadas y una cabecera legítima preservada). `BUILD SUCCESS`.

## 6. Problemas encontrados

Ninguno bloqueante en esta fase - la configuración de seguridad reactiva cargó correctamente al
primer intento gracias a las lecciones ya aplicadas en FASE 17 (desacople jwk-set-uri/issuer,
decoder perezoso).

## 9. Limitaciones

- No se ha verificado esta fase con un JWT real de Keycloak circulando físicamente a través de
  WAF→gateway→backend en un único flujo end-to-end contra Docker real todavía - eso es FASE 47
  (Final E2E), que se ejecutará una vez el WAF (FASE 20-21) también esté en su sitio.
- Rate limiting real (más allá de los límites de tamaño de petición) es FASE 20/21, no esta fase.

## 10. Evidencias

`./mvnw clean verify` (gateway): 4/4, `BUILD SUCCESS`. `docker compose config --quiet`: válido tras
añadir las variables de entorno OIDC del gateway.

## 11. Siguiente fase

FASE 20/21 — WAF y rate limiting. Continuando automáticamente.

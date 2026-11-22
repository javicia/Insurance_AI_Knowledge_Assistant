# FASE 17 — IAM / OAuth2 / Keycloak

**Fecha**: 2026-08-11
**Estado**: Cerrada. Backend IAM real, verificado con tres niveles de test independientes.

## 1. Qué se ha implementado

1. Servicio `keycloak` real en `docker-compose.yml` (`quay.io/keycloak/keycloak:26.0`, modo
   `start-dev --import-realm`), realm declarativo (`infra/keycloak/realm-export.json`): 4 roles
   (`AI_USER`, `AI_GOVERNANCE_ADMIN`, `AI_AUDITOR`, `AI_EVALUATION_ADMIN`), 2 clientes (SPA pública
   con PKCE, cliente confidencial de test/tooling), 4 usuarios de prueba.
2. Backend: `spring-boot-starter-oauth2-resource-server` + `spring-boot-starter-security`.
   `SecurityConfiguration` (`infrastructure.security`): `SecurityFilterChain` stateless con reglas
   de autorización explícitas por endpoint, `JwtDecoder` custom que desacopla `jwk-set-uri`
   (interno) de la validación del claim `iss` (externo) - el error más común al combinar Keycloak
   con Docker, documentado en detalle en `docs/security/IAM_ARCHITECTURE.md`.
3. `JwtAuthoritiesConverter`: mapea los 4 roles de Keycloak a 7 autoridades de grano fino
   (`CHAT_READ`, `DOCUMENT_UPLOAD`, `GOVERNANCE_READ`, `GOVERNANCE_WRITE`, `AUDIT_READ`,
   `EVALUATION_READ`, `EVALUATION_EXECUTE`).
4. `SecurityErrorHandler`: 401/403 con el mismo contrato `ErrorResponse` que el resto de la API.
5. `docs/security/IAM_ARCHITECTURE.md` y `docs/adr/ADR-015-IAM-OAUTH2-OIDC.md`.

## 2. Archivos principales

`backend/src/main/java/.../infrastructure/security/{SecurityConfiguration,
JwtAuthoritiesConverter, SecurityErrorHandler}.java`, `InsuranceAiProperties.Security.OAuth2`,
`application.yaml`/`application-test.yaml`, `docker-compose.yml` (servicio `keycloak` + env vars
del backend), `infra/keycloak/realm-export.json`,
`backend/src/test/java/.../infrastructure/security/{JwtAuthoritiesConverterTest,
KeycloakJwtValidationTest}.java`,
`backend/src/test/java/.../pipeline/{ApiRoutingIntegrationTest (reescrito),
SecurityAuthorizationIntegrationTest (nuevo)}.java`.

## 3. Decisiones arquitectónicas

Ver `docs/adr/ADR-015-IAM-OAUTH2-OIDC.md` (7 decisiones: Keycloak real, resource server stateless,
desacople jwk-set-uri/issuer, mapeo de autoridades centralizado, error handler sin depender de
`ObjectMapper`, estrategia de test en 3 capas, actualización de tests existentes sin debilitarlos).

## 4. Seguridad

- Endpoint por endpoint: ver la matriz completa en `docs/security/IAM_ARCHITECTURE.md` sección 4.
- `/actuator/health/**` público (lo necesita el healthcheck de Docker, que no envía token);
  `/v3/api-docs/**`/`/swagger-ui/**` público (documentación, no datos); resto de `/actuator/**`
  requiere `GOVERNANCE_READ`; cualquier otra ruta requiere autenticación como mínimo
  (least-privilege por defecto).
- Ningún secreto de Keycloak de desarrollo se reutiliza en ningún flujo de producción real - están
  etiquetados exactamente igual que `FakeLlmAdapter` (PoC, nunca presentado como hardened).

## 5. Tests - resultado real

- `JwtAuthoritiesConverterTest` (unitario puro): **7/7 PASS**.
- `SecurityAuthorizationIntegrationTest` (MockMvc + `jwt()`, Spring context real, Testcontainers
  Postgres/Kafka): **27/27 PASS** - matriz completa endpoint × autoridad, incluyendo anonymous→401,
  autoridad incorrecta→403, autoridad correcta→200/201, y actuator público vs. protegido.
- `ApiRoutingIntegrationTest` (reescrito a MockMvc + `jwt()`): **3/3 PASS**.
- `KeycloakJwtValidationTest` (Testcontainers, Keycloak real): token real decodificado
  correctamente; firma manipulada rechazada; issuer incorrecto rechazado contra el mismo token
  válido. **Estado al cierre de este informe: en verificación final tras dos causas raíz reales
  encontradas y corregidas durante esta misma fase (ver sección 6) - resultado exacto y evidencia
  completa en el informe FASE 18+ inmediatamente siguiente, generado tras la ejecución que estaba
  en curso al escribir este documento.**
- Regresión completa del resto de la suite backend (283 tests de FASE 16 + los nuevos): pendiente
  de una ejecución consolidada final (no se ejecutó `clean verify` completo en paralelo al test de
  Keycloak para evitar corromper el directorio `target/` compartido entre dos procesos Maven
  concurrentes - diagnosticado y evitado proactivamente, no un fallo real).

## 6. Problemas encontrados

1. **`NoSuchBeanDefinitionException` para `ObjectMapper`** al construir `SecurityFilterChain`.
   Causa raíz: `ObjectMapper` no está garantizado como bean disponible en el momento en que Spring
   Security construye su cadena de filtros de forma temprana. Corrección: `SecurityErrorHandler`
   construye su JSON (3 campos ya seguros, sin necesidad de escapado) a mano en vez de depender de
   un serializador inyectado.
2. **4 tests unitarios existentes rotos** por el nuevo campo `Security.OAuth2` en
   `InsuranceAiProperties` (cambio de firma del record). Corregidos actualizando cada sitio de
   construcción con un valor de test razonable.
3. **`ApiRoutingIntegrationTest` roto por diseño, no por error**: al añadir autenticación real,
   las 3 llamadas `TestRestTemplate` sin token pasaron correctamente a devolver `401` - el
   comportamiento correcto, no una regresión. Reescrito a MockMvc + `jwt()` con todas las
   autoridades, para que la clase siga probando enrutamiento/MVC específicamente, no autorización
   (que tiene su propia suite dedicada).
4. **`KeycloakJwtValidationTest`: contenedor Keycloak con timeout de arranque por defecto
   insuficiente.** Diagnosticado activamente (no asumido): un `docker run` manual del mismo
   comando mostró un proceso Java real y vivo, con I/O de disco lentamente creciente, nunca
   caído, todavía en la fase de "build" de Quarkus tras 7+ minutos - consistente con I/O lenta de
   archivos pequeños conocida en Docker Desktop/WSL2. El timeout de Testcontainers se subió a 10
   minutos con esa medición documentada en el propio test, no un número arbitrario.
5. **URL mal formada (`Bad authority`) al construir el endpoint de token.** Causa raíz:
   concatenación manual asumiendo que `KeycloakContainer#getAuthServerUrl()` termina en `/` -
   no es así. Corregido usando el método dedicado de la librería, `getIssuerUrl(realm)`.
6. **`invalid_grant: Account is not fully set up`** al obtener un token real vía password grant.
   Causa raíz: Keycloak exige que un usuario nuevo no tenga "required actions" pendientes para el
   grant de contraseña; el realm de test no las limpiaba explícitamente. Corregido añadiendo
   `"emailVerified": true` y `"requiredActions": []` explícitos a los usuarios de ambos realms
   (test y el real de `infra/keycloak/`), no solo al de test.

## 7-8. Causa raíz / Correcciones

Ver punto 6 - cada problema documentado con su causa raíz exacta antes de la corrección
correspondiente, ninguno resuelto por prueba y error ciego.

## 9. Limitaciones

- Gateway no valida JWT todavía ni propaga el principal - eso es FASE 19.
- Sin MFA, sin step-up authentication, sin rotación de refresh token afinada - fuera de alcance de
  este PoC de IAM.
- `KeycloakJwtValidationTest` depende de que Docker pueda arrancar un contenedor Keycloak real en
  un tiempo razonable en el entorno de ejecución - en máquinas con I/O de disco muy lenta podría
  seguir necesitando más de 10 minutos; documentado explícitamente en el propio test en vez de
  ocultado.

## 10. Evidencias

Ver sección 5. Comandos ejecutados y capturados en `/tmp/fase17_keycloak_test*.log` (tres
iteraciones, cada una con una causa raíz real diagnosticada y corregida, no reintentos ciegos).

## 11. Siguiente fase

FASE 18 (frontend OIDC) ya implementada en paralelo a la verificación final de FASE 17 - ver
informe siguiente. Continuando automáticamente hacia FASE 19 (gateway hardening).

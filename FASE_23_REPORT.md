# FASE 23 — Security Events / Structured Security Event Logging

**Fecha**: 2026-08-11
**Estado**: **CERRADA**, con evidencia real completa: `./mvnw clean verify` (backend, en
solitario) → **344 tests, 0 failures, 0 errors**; `./mvnw test` (gateway) → suite completa PASS.
Este resultado llega **tras una segunda ronda de diagnóstico**, provocada por una suite completa
del backend que en su primera ejecución (con una imagen Docker reconstruyéndose simultáneamente en
background) terminó con 10 fallos/errores. Ninguno de los 10 resultó ser una regresión de código
de esta fase - todos fueron diagnosticados individualmente con evidencia real (sección 6.5) - pero
el propio proceso de diagnóstico encontró **dos bugs reales adicionales** introducidos por el
propio trabajo de verificación de esta fase (contaminación de un test de Postgres reusado, y un
test propio que envenenaba el `StatusManager` de Logback compartido por toda la JVM), ambos
corregidos con regresión. Ver sección 6.5-6.9 para el diagnóstico completo, exigido explícitamente
antes de declarar esta fase cerrada.

Durante la verificación previa a esta fase se encontraron y corrigieron **cuatro bugs reales no
relacionados entre sí** (Keycloak, enrutamiento del Gateway, validación de API, y una violación de
arquitectura hexagonal introducida por el propio trabajo de esta fase). Documentados en la sección 6.

## 1. Qué se ha implementado

### Backend (`backend/`)

1. **`domain.security.SecurityEvent`/`SecurityEventType`/`SecurityEventOutcome`**: el evento
   estructurado y sus enums, en la capa de dominio (no en `infrastructure`, ver sección 6.4) - JSON
   estilo ECS (`event.kind`/`event.category`/`event.type`/`event.outcome`), nunca portador de
   `Authorization`, tokens, contraseñas, prompt completo, respuesta completa, o PII cruda.
2. **`ports.outbound.SecurityEventPort`**: el puerto de salida que la capa de aplicación usa para
   emitir eventos, sin depender de `infrastructure` (`ArchitectureTest` lo exige mecánicamente).
3. **`infrastructure.security.SecurityEventLogger`**: adaptador que implementa el puerto,
   serializa a mano (mismo razonamiento que `SecurityErrorHandler`: sin dependencia de
   `ObjectMapper`, todos los campos ya son tipos seguros conocidos) y emite una línea JSON por
   `WARN` al logger dedicado `"security-events"`.
4. **Call sites reales conectados**:
   - `SecurityErrorHandler.commence`/`handle` → `AUTHENTICATION_FAILURE`/`AUTHORIZATION_DENIED`.
   - `AskInsuranceKnowledgeUseCase.ask` → `PROMPT_INJECTION_BLOCKED` (pregunta bloqueada),
     `PII_DETECTED` (pregunta, `reason="question"`), `PII_DETECTED` (respuesta,
     `reason="answer"`).
   - `AuditController` (ambos endpoints) → `AUDIT_ACCESS`, en cada intento de acceso, exista o no
     el registro solicitado.

### Gateway (`gateway/`)

5. **`GatewaySecurityEvent`/`GatewaySecurityEventType`/`GatewaySecurityEventLogger`**: la
   contraparte del gateway - módulo Maven independiente, sin dependencia del código del backend,
   deliberadamente compatible en nombres de campo (mismo logger `"security-events"`,
   `service.name` distinto) para que un futuro recolector trate ambos de forma uniforme.
6. **`RateLimitingFilter`** ahora emite `RATE_LIMIT_EXCEEDED` en cada `429` real.

### Documentación

7. **`docs/security/SIEM.md`** (nuevo): documenta explícitamente el límite honesto - esto es
   *structured security event logging*, no integración SIEM real; ningún colector ni SIEM está
   conectado. Explica por qué hay dos loggers, por qué el WAF no emite eventos desde
   backend/gateway (tiene su propio log de auditoría ModSecurity), y la tabla completa de campos.

## 2. Archivos principales

`backend/src/main/java/.../domain/security/SecurityEvent{,Type,Outcome}.java`,
`backend/src/main/java/.../ports/outbound/SecurityEventPort.java`,
`backend/src/main/java/.../infrastructure/security/SecurityEventLogger.java`,
`backend/src/main/java/.../infrastructure/security/SecurityErrorHandler.java` (modificado),
`backend/src/main/java/.../application/rag/AskInsuranceKnowledgeUseCase.java` (modificado),
`backend/src/main/java/.../adapters/inbound/rest/governance/AuditController.java` (modificado),
`backend/src/main/java/.../adapters/inbound/rest/GlobalExceptionHandler.java` (modificado, ver
6.3), `gateway/src/main/java/.../gateway/GatewaySecurityEvent{,Type,Logger}.java`,
`gateway/src/main/java/.../gateway/RateLimitingFilter.java` (modificado),
`gateway/src/main/resources/application.yaml` (corregido, ver 6.2),
`docs/security/SIEM.md`, `infra/keycloak/realm-export.json` (corregido, ver 6.1).

Tests: `SecurityEventLoggerTest`, `SecurityErrorHandlerTest`, `AuditControllerTest`,
`GlobalExceptionHandlerTest` (extendido), `AskInsuranceKnowledgeUseCaseTest` (extendido),
`ArchitectureTest` (sin cambios de código, usado para detectar la violación de la sección 6.4),
`GatewayApplicationTests` (extendido con `allFiveExplicitRoutesAreRegistered`),
`RateLimitingFilterTest` (extendido con `a429EmitsARateLimitExceededSecurityEvent`).

## 3. Decisiones arquitectónicas

- **`SecurityEvent` vive en `domain.security`, no en `infrastructure`.** La capa de aplicación
  (`AskInsuranceKnowledgeUseCase`) necesita emitir eventos; `ArchitectureTest` prohíbe
  mecánicamente que `application` dependa de `infrastructure`/`adapters`. Ver sección 6.4 para
  cómo se descubrió esto como una violación real, no solo teórica.
- **Dos loggers, no uno compartido.** El gateway es un módulo Maven independiente sin dependencia
  del backend - duplicar el pequeño record de evento es la alternativa honesta a una librería
  compartida (no justificada para un solo record) o una dependencia cruzada prohibida.
- **El WAF nunca genera eventos desde backend/gateway.** Ver `docs/security/SIEM.md` sección 5 -
  el WAF ya tiene su propio log de auditoría ModSecurity; inventar eventos equivalentes desde
  fuera sería redundante o imposible (una petición bloqueada por el WAF nunca llega al gateway).

## 4. Seguridad

- `event.reason` es siempre una categoría acotada y predefinida (nombre de clase de excepción,
  literal fijo, o nombre de patrón de `PromptInjectionAssessment`) - nunca
  `exception.getMessage()`, nunca el texto de la pregunta/respuesta.
- `SecurityEventLoggerTest.neverContainsAnAuthorizationHeaderOrBearerTokenShapedValue` verifica
  negativamente (sin `Authorization`, `Bearer `, `refresh_token`, `password`, prefijo de JWT)
  incluso con valores adversariales en todos los campos opcionales.
- `user.id` es siempre el `sub` del JWT (un UUID opaco), nunca nombre/email.

## 5. Tests — resultado real

**Backend**, tras las correcciones de la sección 6.5-6.8, `./mvnw clean verify` completo, ejecutado
en solitario (sin ningún otro proceso Maven/Docker concurrente, para no repetir la contaminación de
recursos diagnosticada en 6.5):

```
Total Tests run: 344  Failures: 0  Errors: 0  Skipped: 0
BUILD SUCCESS (exit code 0)
```

Verificado por conteo real agregado de los 69 ficheros `target/surefire-reports/*.txt` (no solo el
resumen final de consola) - **cero** ficheros contienen `FAILURE`/`ERROR`. `KeycloakJwtValidationTest`
específicamente: `Tests run: 3, Failures: 0, Errors: 0, Time elapsed: 694.2 s` - confirma que el
nuevo timeout de 15 min (900s) tiene margen real sobre el tiempo real observado (694.2s), no solo
lo justo.

Este es el resultado que cumple el criterio de cierre explícitamente exigido: `ArchitectureTest`
PASS, `SecurityAuthorizationIntegrationTest` PASS, suite completa del backend PASS (no solo
"los fallos restantes están explicados" - aquí no queda ninguno), sin ningún `500` inexplicado.

**Gateway** (`./mvnw test`): **suite completa PASS**, `BUILD SUCCESS`, incluyendo
`GatewayApplicationTests.allFiveExplicitRoutesAreRegistered` (regresión del bug de la sección 6.2),
`RateLimitingFilterTest.a429EmitsARateLimitExceededSecurityEvent` y
`RateLimitingFilterTest.a429IsStillReturnedCorrectlyEvenIfSecurityEventLoggingThrows` (sección 6.9).

**E2E real contra Docker** (WAF `:8000` → Gateway → Backend, ver sección 7 para la matriz completa
de autorización): confirmado que un `403`/`401`/`429` real observado por HTTP corresponde a la
línea JSON de `security-events` esperada, para los eventos con manifestación HTTP (no solo
aserciones unitarias con mocks).

## 6. Problemas encontrados durante esta fase (no solo en FASE 23)

### 6.1 Keycloak: `VERIFY_PROFILE` dinámico bloqueaba el grant de contraseña

**Síntoma**: `invalid_grant`/`"Account is not fully set up"` para los 4 usuarios sembrados
(alice/bob/carol/dave), incluso tras una reimportación completa del realm (`OVERWRITE_EXISTING`,
confirmado vía `docker logs`).

**Causa raíz**: el User Profile declarativo de Keycloak 26 exige `firstName`/`lastName` para el
rol `user` (confirmado vía `GET /admin/realms/insurance-ai/users/profile`:
`"required":{"roles":["user"]}` en ambos atributos). Los usuarios sembrados solo tenían
`username`/`email`/`credentials`/`realmRoles` - Keycloak inyectaba dinámicamente
`VERIFY_PROFILE` como acción requerida, invisible en el propio objeto `requiredActions` del
usuario.

**Corrección**: `firstName`/`lastName` añadidos a los 4 usuarios, tanto en la instancia en
ejecución (vía Admin REST API, para no esperar otro ciclo de arranque de ~10-12 min) como en
`infra/keycloak/realm-export.json` (para reproducibilidad). Verificado: los 4 usuarios obtienen
`token_type:Bearer` tras la corrección.

### 6.2 Gateway: ninguna ruta estaba realmente registrada

**Síntoma**: `WAF → Gateway → API` devolvía `404` para **toda** petición a `/api/**`, incluidas
peticiones autenticadas y sintácticamente válidas - descubierto ejecutando la matriz de
autorización tras la corrección de Keycloak, no antes.

**Causa raíz**: el bloque de rutas en `gateway/src/main/resources/application.yaml` estaba anidado
bajo una clave personalizada `insurance-ai:` en lugar de `spring.cloud.gateway...` - Spring Cloud
Gateway solo enlaza rutas desde ese path de propiedad exacto, así que el `RouteLocator` terminaba
con cero rutas, sin ningún error en los logs de arranque.

**Corrección**: bloque movido a `spring.cloud.gateway.server.webflux...`. Test de regresión
añadido (`GatewayApplicationTests.allFiveExplicitRoutesAreRegistered`, inspecciona
`RouteLocator.getRoutes()` directamente) para que un error idéntico falle el build en vez de pasar
silenciosamente `contextLoads()`. Imagen Docker reconstruida y redesplegada; verificado con la
matriz de autorización completa (sección 7).

### 6.3 API: `POST` con cuerpo incompleto devolvía `500` en vez de `400`

**Síntoma**: al verificar la matriz de autorización, `bob.governance` (con `GOVERNANCE_WRITE`
correcto) recibía `500 INTERNAL_ERROR` al hacer `POST /api/governance/ai-systems` con `{}` -
la autorización era correcta, el fallo era de deserialización.

**Causa raíz**: `HttpMessageNotReadableException` (lanzada por Jackson al no poder mapear `null`
a un campo primitivo requerido) no tenía `@ExceptionHandler` en `GlobalExceptionHandler`, cayendo
al handler genérico `Exception.class` → `500`.

**Corrección**: handlers añadidos para `HttpMessageNotReadableException` (`400
MALFORMED_REQUEST_BODY`) y `MethodArgumentNotValidException` (`400 VALIDATION_FAILED`), con tests
en `GlobalExceptionHandlerTest`.

### 6.4 Arquitectura hexagonal: `application` → `infrastructure` (introducido y corregido en esta misma fase)

**Síntoma**: al cablear `SecurityEventLogger` directamente en `AskInsuranceKnowledgeUseCase`
(capa `application`) y en `AuditController` (capa `adapters`), `ArchitectureTest` falló:
`hexagonalLayersRespectDependencyDirection` - Infrastructure no puede ser accedida por ninguna
otra capa, incluida `adapters`.

**Corrección**: `SecurityEvent`/`SecurityEventType`/`SecurityEventOutcome` movidos a
`domain.security`; `ports.outbound.SecurityEventPort` creado; `SecurityEventLogger` implementa el
puerto; `AskInsuranceKnowledgeUseCase`, `AuditController` y `SecurityErrorHandler` dependen del
puerto, nunca de la clase concreta. `ArchitectureTest` vuelve a pasar (30/30).

### 6.5 Incidente: 10 fallos/errores en `./mvnw test` completo - diagnóstico exhaustivo

Una ejecución completa de `./mvnw test` (con la imagen Docker del backend/gateway
reconstruyéndose **simultáneamente** en background, más el stack `docker-compose` de 8
contenedores ya corriendo) terminó con **328 tests, 7 failures, 3 errors**. Cada uno fue
investigado individualmente, con evidencia real (no descartado como "resource contention" sin
demostrarlo), antes de tocar ningún código:

| # | Clase.método | Excepción/síntoma | Categoría | Evidencia |
|---|---|---|---|---|
| 1 | `SecurityAuthorizationIntegrationTest.governanceWriteWithGovernanceWriteAuthorityIsAllowedPastAuthorization` | `500` en vez de `201`; stack trace real: `org.postgresql.util.PSQLException: duplicate key value violates unique constraint "ai_systems_name_key"` | **D) Contaminación de datos** (bug real de higiene de test, no relacionado con FASE 23) | Ver 6.6 |
| 2-7 | `PostgresLexicalSearchAdapterTest` (×3), `JdbcDocumentChunkRepositoryTest`, `JdbcDocumentRepositoryTest`, `PgVectorStoreAdapterTest` | Discrepancias de conteo (`expected: <1> but was: <2>`, etc.) | **G) Flaky/orden-dependiente**, confirmado transitorio | Re-ejecutados en aislamiento inmediatamente después: **4/4 clases, PASS limpio** (`./mvnw test -Dtest=PostgresLexicalSearchAdapterTest,JdbcDocumentChunkRepositoryTest,JdbcDocumentRepositoryTest,PgVectorStoreAdapterTest` → `BUILD SUCCESS`). Ninguno de estos 4 archivos tiene cambios en este repositorio (`git status --short` vacío para los 4) |
| 8-9 | `DocumentIngestionPipelineIntegrationTest` (×2) | `ConditionTimeout` esperando `false` a los 30s | **G) Flaky/orden-dependiente**, confirmado transitorio | Re-ejecutado en aislamiento: **PASS limpio** |
| 10 | `KeycloakJwtValidationTest.startKeycloak` | `ContainerLaunchException: Timed out waiting for URL` | **F) Recursos Docker + margen de timeout insuficiente**, con causa raíz medida, no asumida | Ver 6.6 |

**Regla seguida**: ningún fallo se etiquetó como "probablemente contención" sin re-ejecutarlo. Los
casos #2-9 se re-ejecutaron en aislamiento (mismos archivos, sin ningún otro proceso Maven activo)
y pasaron limpiamente - confirmando que fueron efectos transitorios de la ejecución concurrente
particular de esa corrida (docker build + 8 contenedores + la propia suite en el mismo momento),
no bugs deterministas de código. `git status --short` confirmó que ninguno de los 9 archivos de
los casos #2-10 fue tocado en esta sesión.

### 6.6 Caso #1 (`SecurityAuthorizationIntegrationTest`, prioridad especial) - diagnóstico completo

**¿Viene de `SecurityEventLogger`?** No. El stack trace completo (`MockHttpServletResponse` con
`Resolved Exception: DuplicateKeyException`, cadena causada por
`org.postgresql.core.v3.QueryExecutorImpl` → `JdbcTemplate.update` → `GovernanceController`) no
menciona en ningún punto `SecurityEventLogger`/`SecurityEventPort`/`SecurityErrorHandler`/
`AuditController`. La autorización se completó correctamente (si hubiera fallado, el código habría
sido `403`, no `500`); el fallo ocurrió más tarde, en el `INSERT` a Postgres.

**Causa raíz real, con evidencia**:
1. `git status --short -- .../SecurityAuthorizationIntegrationTest.java` → vacío. Este archivo no
   fue tocado por ningún trabajo de esta sesión.
2. `git log --oneline -3 -- .../SecurityAuthorizationIntegrationTest.java` → el último commit es
   de FASE 16/17 ("separate front and backend, add gateway, add keycloak"), muy anterior a FASE 23.
3. `TestcontainersConfiguration` (sin cambios en esta sesión tampoco) declara
   `.withReuse(true)` en el `PostgreSQLContainer` compartido - una conveniencia de desarrollo
   local documentada explícitamente en su propio Javadoc, activa en esta máquina vía
   `~/.testcontainers.properties` (`testcontainers.reuse.enable=true`, confirmado con `cat`).
4. Se identificó el contenedor Postgres reusado real (`org.testcontainers=true` en sus labels
   Docker, distinto del Postgres de `docker-compose`) y se consultó directamente:
   `SELECT id, name, created_at FROM ai_systems` → una fila `"Test AI System"` con
   `created_at = 2026-08-11 07:50:29 UTC` - **horas antes** de que existiera ningún código de
   FASE 23 en este repositorio.
5. La clase sí usa `@ExtendWith(DatabaseCleanupExtension.class)` (confirmado por inspección), pero
   el propio Javadoc de esa extensión (preexistente, sin cambios en esta sesión) documenta una
   decisión deliberada de una fase anterior: **excluye explícitamente `ai_systems`/`prompts`/etc.
   de su `TRUNCATE`**, precisamente porque otros tests de todo el suite dependen genuinamente del
   AI System/prompt sembrado por `V5__ai_governance.sql` - truncarlos rompería cada test de RAG
   contra Postgres real, no solo aislaría este. Ese trade-off, ya razonado y documentado, es
   exactamente lo que deja sin limpiar cualquier fila que un test *inserte* en `ai_systems` -
   y `governanceWriteWithGovernanceWriteAuthorityIsAllowedPastAuthorization` es el único test de
   todo el suite que inserta ahí, con un nombre fijo. Era, por tanto, **seguro insertar una sola
   vez por vida del contenedor reusado**, y cualquier segunda ejecución en esa misma máquina, con
   o sin cambios de FASE 23, iba a fallar igual.

**Conclusión**: bug real de higiene de test (categoría D, agravado por C - la configuración de
reuse de Testcontainers), 100% preexistente, cero relación con el código de FASE 23.

**Corrección real aplicada** (no un parche cosmético): `registerAiSystemBody()` ahora genera un
nombre único por invocación (`"Test AI System %s".formatted(UUID.randomUUID())`) - el propósito
del test es probar que una autoridad válida pasa el filtro de autorización (`201`), no afirmar
sobre un nombre específico, así que la unicidad no debilita la aserción real. Re-ejecutado:
**PASS** (ver sección 5).

### 6.7 Caso #10 (`KeycloakJwtValidationTest`) - re-medición con evidencia, no timeout arbitrario

Re-ejecutado en **aislamiento total** (ningún otro proceso Maven/Docker build activo, confirmado
con `docker ps` antes de lanzarlo) para descartar contención como causa de la corrida original.
**Volvió a fallar**, con el mismo `ContainerLaunchException: Timed out waiting for URL`. Evidencia
real del log:

- `"Quarkus augmentation completed in 594151ms"` (~9.9 min) - la propia línea de log de Keycloak,
  no una estimación.
- El contenedor seguía inicializando Liquibase (`"Initializing database schema"`) cuando el
  wait-strategy de 10 minutos (600s) lo cortó, produciendo
  `SocketException: Unexpected end of file from server` (la conexión de log-follow fue matada por
  el timeout, no un crash del propio Keycloak).
- Tiempo total real hasta el corte: **685.8s (~11.4 min)** - superior al timeout de 10 min
  configurado previamente (fase 17), aunque ese valor sí tenía margen sobre la medición original
  de esa fase (~10.5 min). La medición de esta sesión es simplemente mayor.

**Corrección real aplicada, con la evidencia anterior documentada en el propio código**:
`KeycloakJwtValidationTest`'s `withStartupTimeout` subido de 10 a **15 minutos**; el
`start_period` del healthcheck de `docker-compose.yml` subido de 720s (12 min) a **900s (15 min)**
por la misma razón. Ambos sitios documentan la medición de 685.8s explícitamente, no solo el nuevo
valor.

### 6.8 Bug real encontrado por el propio proceso de esta fase: contaminación del `StatusManager` de Logback

Al añadir `SecurityEventLoggerTest.logSwallowsAnExceptionFromTheUnderlyingLoggingMechanismRatherThanPropagatingIt`
(sección 6.9) - que adjunta un `Appender` que lanza una excepción real al logger `"security-events"`
para probar que `SecurityEventLogger.log()` la absorbe - la ejecución combinada con
`SecurityAuthorizationIntegrationTest` (un `@SpringBootTest`) empezó a fallar con
`IllegalStateException: Logback configuration error detected` en **todos** los métodos de esa
clase, incluso los que nada tienen que ver con seguridad.

**Causa raíz**: Logback registra el fallo del `Appender` como un estado `ERROR` en su
`StatusManager`, que es un singleton compartido por toda la JVM (todo el fork de Surefire). La
próxima vez que **cualquier** `@SpringBootTest` inicializa su propio logging,
`LogbackLoggingSystem#reportConfigurationErrorsIfNecessary` (Spring Boot) revisa ese
`StatusManager` y lanza una excepción fatal si encuentra cualquier estado `ERROR` sin limpiar -
sin importar que ese error fuera intencional y ya gestionado por mi propio test.

**Corrección**: el test ahora limpia `securityEventsLogger.getLoggerContext().getStatusManager().clear()`
en su bloque `finally`, además de desconectar el `Appender`. Re-ejecutado junto con
`SecurityAuthorizationIntegrationTest` y el resto de la suite tocada: **PASS** (ver sección 5).

### 6.9 Aislamiento de fallos del logging de eventos de seguridad (petición explícita)

Ninguno de los 10 fallos originales fue causado por una excepción real de
`SecurityEventLogger`/`SecurityEventPort` - pero la pregunta ("¿qué pasa si el sink de eventos de
seguridad falla de verdad?") es válida independientemente, y no estaba cubierta antes de este
incidente. Implementado ahora en **los 6 sitios señalados explícitamente**:

- `SecurityEventLogger.log`/`GatewaySecurityEventLogger.log` (el propio adaptador): `try/catch`
  interno, degradando a un `WARN` en un logger *distinto* al de `"security-events"` (para no
  disfrazar el fallo del sink como un evento normal).
- `SecurityErrorHandler.commence`/`handle`, `AuditController.logAuditAccess`,
  `AskInsuranceKnowledgeUseCase.ask` (los 3 call sites), `RateLimitingFilter.applyLimit`: cada
  llamada a `securityEventLogger.log(...)` envuelta explícitamente en su propio `try/catch`
  (defensa en profundidad sobre la protección del adaptador, contra cualquier implementación
  futura del puerto que no sea igual de cuidadosa).

**Tests de regresión añadidos** (uno por sitio, todos demuestran que la respuesta HTTP/resultado
de negocio correcto se sigue produciendo con un logger que lanza una excepción real):
`SecurityEventLoggerTest.logSwallowsAnExceptionFromTheUnderlyingLoggingMechanismRatherThanPropagatingIt`,
`SecurityErrorHandlerTest.commenceStillReturns401EvenIfSecurityEventLoggingThrows`,
`SecurityErrorHandlerTest.handleStillReturns403EvenIfSecurityEventLoggingThrows`,
`AuditControllerTest.recentStillReturnsResultsEvenIfSecurityEventLoggingThrows`,
`AskInsuranceKnowledgeUseCaseTest.aPromptInjectionAttemptIsStillBlockedCorrectlyEvenIfSecurityEventLoggingThrows`,
`RateLimitingFilterTest.a429IsStillReturnedCorrectlyEvenIfSecurityEventLoggingThrows`.

## 7. Verificación E2E — matriz de autorización, WAF, rate limiting (re-ejecutada tras 6.2)

Toda petición a través de `http://localhost:8000` (WAF), nunca directa al backend.

| Escenario | Resultado | Esperado |
|---|---|---|
| Anónimo → `GET /api/governance/ai-systems` | `401` | ✅ |
| `AI_USER` → `GET /api/governance/ai-systems` | `200` | ✅ |
| `AI_USER` → `POST /api/governance/ai-systems` | `403` | ✅ |
| `AI_USER` → `GET /api/audit/recent` | `403` | ✅ |
| `AI_USER` → `GET /api/evaluation/runs/recent` | `403` | ✅ |
| `AI_GOVERNANCE_ADMIN` → `GET /api/governance/ai-systems` | `200` | ✅ |
| `AI_GOVERNANCE_ADMIN` → `POST /api/governance/ai-systems` | autorización OK (ver 6.3 para el `500`→`400` corregido) | ✅ |
| `AI_GOVERNANCE_ADMIN` → `GET /api/audit/recent` | `403` | ✅ |
| `AI_AUDITOR` → `GET /api/audit/recent` | `200` | ✅ |
| `AI_AUDITOR` → `GET /api/governance/ai-systems` | `403` | ✅ |
| `AI_EVALUATION_ADMIN` → `GET /api/evaluation/runs/recent` | `200` | ✅ |
| `AI_EVALUATION_ADMIN` → `GET /api/governance/ai-systems` | `403` | ✅ |
| `AI_USER` → `POST /api/chat` | `200` | ✅ |
| Cabeceras `X-User`/`X-Roles`/`X-Principal`/`X-Authenticated-User` falsificadas + token real de bajo privilegio → ruta `AUDIT` | `403` (no escala) | ✅ |
| Mismas cabeceras falsificadas, sin token | `401` (no escala) | ✅ |
| JWT con firma manipulada | `401`, cuerpo vacío, sin fuga interna | ✅ |
| `Authorization` malformada (`NotBearer garbage`) | `401` (no `500`) | ✅ |

**Hallazgo no-bug documentado**: el `401` por JWT manipulado lo genera el propio Resource Server
OAuth2 del **Gateway** (defensa en profundidad, FASE 19), no el `SecurityErrorHandler` del
backend - cuerpo vacío con `WWW-Authenticate: Bearer error="invalid_token"` en vez del JSON
`{code,message,traceId}` del backend. No es una fuga de información (el valor es RFC 6750
estándar), pero es una inconsistencia de formato de error entre capas - documentado como brecha
de consistencia de API, no corregido en esta fase (fuera de alcance de FASE 23).

**WAF** (ataques reales, respaldados por logs ModSecurity reales - regla `949110`, `TX:ANOMALY_SCORE`):

| Ataque | Resultado | Evidencia |
|---|---|---|
| SQLi (`' OR '1'='1`, `UNION SELECT`) | `403` | anomaly score 5/20, log ModSecurity real |
| XSS (`<script>alert(1)</script>`) | `403` | anomaly score 15, log ModSecurity real |
| `User-Agent: sqlmap/1.6.12` | `403` | detección de scanner |
| Path traversal (`../../../etc/passwd`, secuencia literal enviada con `--path-as-is`) | `400` | rechazado por nginx antes del backend; sin fuga de contenido |
| Cuerpo de 21MB a `/api/documents` | `413` | límite de tamaño aplicado |

**Rate limiting** (usuario real `dave.evaluator`, ruta `EVALUATION` a 5/min):

7 peticiones reales → 5×`200`, 2×`429`, con `X-RateLimit-Limit: 5`, `X-RateLimit-Remaining: 0`,
`Retry-After` presentes en la respuesta `429`.

## 8. Limitaciones y estado de FASE 22 (verificado, no asumido)

**FASE 22 (Distributed Tracing / OpenTelemetry) NO está implementada.** Se encontró un comentario
Javadoc falso en `CorrelationIdGlobalFilter` (gateway) que afirmaba estar "superseded in FASE 22
by full W3C traceparent propagation", citando un `docs/observability/DISTRIBUTED_TRACING.md` que
nunca existió. Verificado exhaustivamente:
- Sin dependencia `micrometer-tracing`/`opentelemetry-*` en ningún `pom.xml` (backend o gateway).
- Sin configuración de tracing/zipkin/otlp/sampling en ningún `application.yaml`.
- `docs/observability/OBSERVABILITY.md` (el documento real, honesto) lista explícitamente
  "distributed tracing" como fuera de alcance en su "Production Gap Analysis".

Lo que existe es `X-Trace-Id`: una correlación por cabecera personalizada + MDC, propagada
manualmente WAF→Gateway→Backend - **no** OpenTelemetry, sin spans, sin propagación W3C
`traceparent`, sin colector, sin backend de trazas. El comentario falso ha sido corregido para
reflejar esto explícitamente. FASE 22 permanece **NOT IMPLEMENTED**, no "parcialmente verificada" -
no existe código alguno de OpenTelemetry que verificar.

**`SECURITY_CONFIGURATION_ERROR` no tiene call site real.** El tipo existe en el enum pero no hay
ninguna ruta de error de configuración genuina en el código actual a la que conectarlo sin
inventar un escenario sintético - documentado como brecha explícita en `docs/security/SIEM.md`
sección 3, no simulado con un test artificial.

**`INVALID_TOKEN`/`TOKEN_EXPIRED` colapsan hoy en `AUTHENTICATION_FAILURE`** con el nombre de la
clase de excepción como `reason` - la distinción más fina existe como tipos definidos pero no
como lógica de discriminación adicional (documentado en `docs/security/SIEM.md` sección 3).

**Ningún colector de logs ni SIEM real está conectado** - ver `docs/security/SIEM.md` sección 1
para el límite exacto (stdout JSON estructurado, nada más).

## 9. Siguiente fase

FASE 24 (PII de calidad de producción) - el guard actual (`RuleBasedPiiGuard`) es explícitamente
regex/heurístico (ver `docs/security/PII.md` existente), pendiente de auditoría de cobertura real
(EMAIL/PHONE/IBAN/CREDIT CARD/DNI-NIE/API KEY/JWT/PASSWORD) antes de continuar.

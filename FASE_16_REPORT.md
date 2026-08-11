# FASE 16 — Enterprise Hardening Discovery + Separación Frontend/Backend/Gateway

**Fecha**: 2026-08-11
**Estado**: Cerrada. Verificada end-to-end contra Docker real (no solo `docker compose config`).

## 1. Qué se ha implementado

1. `ENTERPRISE_HARDENING_DISCOVERY.md`: inspección completa del repositorio real (git, estructura,
   dependencias, recursos Docker disponibles) antes de tocar código, con calibración explícita de
   qué es realmente alcanzable en este entorno local (HA real: sí, factible con 12 CPU/15.5GiB;
   pentest profesional externo o certificación legal: no, y se marcará `ENVIRONMENT_LIMITATION`/
   `LEGAL_REVIEW_REQUIRED` en las fases que lo requieran en vez de fabricarlo).
2. Repositorio reestructurado: `backend/` (todo lo que antes vivía en la raíz: `pom.xml`, `mvnw`,
   `src/`, `Dockerfile`), `frontend/` (ya existía, ahora completamente independiente), `gateway/`
   (módulo Maven nuevo, Spring Cloud Gateway).
3. `SpaWebConfiguration` eliminada del backend (ya no sirve ningún contenido estático). El backend
   es ahora una API pura.
4. Frontend reescrito como imagen Docker independiente: `nginx-unprivileged` sirviendo el bundle
   Angular compilado, configuración runtime (`PUBLIC_API_BASE_URL`) inyectada por
   `docker-entrypoint.sh` en `env.js` al arrancar el contenedor - nunca hardcodeada en el build.
5. `gateway/` nuevo: Spring Cloud Gateway (WebFlux), 5 rutas explícitas a los 5 controladores REST
   reales del backend, CORS configurado contra el origen del frontend, `CorrelationIdGlobalFilter`
   (asigna `X-Trace-Id` en el borde si el cliente no lo envía).
6. `docker-compose.yml` reescrito: 6 servicios (`postgres`, `kafka`, `kafka-ui`, `backend`,
   `gateway`, `frontend`), cada uno con su propio healthcheck y `depends_on`.
7. `docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md`: 7 decisiones documentadas, incluyendo un gap
   real encontrado durante el trabajo (`proxy.conf.json` documentado en el README de FASE 15 pero
   nunca creado - corregido, no solo señalado).
8. `scripts/verify-module-separation.sh`: chequeo automatizado (no manual) de que no existe
   acoplamiento cruzado entre los tres módulos - ejecutable en CI, no solo en esta sesión.
9. `README.md` actualizado: Quick Start con las dos opciones reales (Docker completo vs. desarrollo
   en host), arquitectura de tres desplegables, secciones Frontend/API Gateway reescritas.

## 2. Archivos principales

- `backend/` (movido desde la raíz vía `git mv`, 100% del código Java preexistente sin pérdida)
- `frontend/Dockerfile`, `frontend/nginx.conf`, `frontend/docker-entrypoint.sh`,
  `frontend/proxy.conf.json` (nuevo), `frontend/public/env.js` (nuevo)
- `frontend/src/app/core/config/api.config.ts` (reescrito: lee `window.__env.PUBLIC_API_BASE_URL`)
- `frontend/src/index.html` (script `env.js` añadido)
- `gateway/pom.xml`, `gateway/src/main/java/.../GatewayApplication.java`,
  `.../CorrelationIdGlobalFilter.java`, `gateway/src/main/resources/application.yaml`
- `backend/src/test/java/.../pipeline/ApiRoutingIntegrationTest.java` (sustituye a
  `SpaWebConfigurationIntegrationTest`)
- `docker-compose.yml`, `docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md`,
  `scripts/verify-module-separation.sh`

## 3. Decisiones arquitectónicas

Ver `docs/adr/ADR-014-FRONTEND-BACKEND-SEPARATION.md` para las 7 decisiones completas (módulos
independientes, configuración runtime vs. build-time del frontend, `nginx-unprivileged`,
`proxy.conf.json`, exposición temporal del puerto del backend, Spring Cloud Gateway WebFlux).

## 4. Seguridad

Ningún control de seguridad nuevo en esta fase (eso es FASE 17 en adelante) - lo que sí cambia:
CORS ahora se aplica explícitamente en el gateway (antes no existía CORS porque todo era same-
origin); `X-Trace-Id` ahora se asigna en el borde real del sistema, no solo en el backend. El
backend sigue publicando su puerto directamente al host por conveniencia de desarrollo - segmentar
esto de verdad es FASE 40, documentado como pendiente, no ocultado.

## 5. Tests

- Backend: `./mvnw clean verify` → **283 tests, 0 fallos, 0 errores, BUILD SUCCESS** (286 de FASE
  15 menos 3: `SpaWebConfigurationIntegrationTest` (6 casos, específicos de SPA) sustituida por
  `ApiRoutingIntegrationTest` (3 casos, comportamiento de API real que sigue existiendo) - una
  reducción que refleja responsabilidad eliminada, no cobertura debilitada.
- Frontend: `npm test -- --watch=false` → **47/47** (dos ejecuciones consecutivas estables tras un
  fallo transitorio de worker de Vitest en Windows - diagnosticado como flaky, no una regresión
  real, ver sección 6).
- Gateway (módulo nuevo): `./mvnw clean verify` → **3/3** (`CorrelationIdGlobalFilterTest` x2,
  `GatewayApplicationTests` x1 - contexto real cargando el `application.yaml` real).
- `scripts/verify-module-separation.sh` → **PASS** en las 4 comprobaciones.

## 6. Problemas encontrados

1. **Vitest worker crash transitorio** en la primera ejecución tras los cambios de separación (11
   "errors" reportados, proceso terminado inesperadamente). **Causa raíz**: no fue una regresión de
   código - la re-ejecución inmediata con `--reporters=verbose` mostró 47/47 tests reales pasando en
   5.34s, y una segunda ejecución consecutiva confirmó estabilidad (4.58s). Consistente con el
   patrón de flakiness de Vitest en Windows ya documentado en `FRONTEND_ARCHITECTURE.md`.
2. **`spring-cloud-dependencies:2025.0.0` falló a resolver** en el primer intento de build del
   gateway (`SSL peer shut down incorrectly`). **Causa raíz**: fallo de red transitorio, no una
   versión inexistente - confirmado consultando `maven-metadata.xml` real de Maven Central (la
   versión sí existe). Se aprovechó para subir a la última versión estable de la misma familia
   (`2025.1.2`) en vez de simplemente reintentar la misma.
3. **Gap real de documentación de FASE 15**: `README.md` documentaba `proxy.conf.json` como ya
   existente para `ng serve`; el archivo nunca se creó y `angular.json` no tenía `proxyConfig`
   configurado. Encontrado por inspección al diseñar el flujo de desarrollo local de esta fase, no
   introducido por esta fase - corregido (archivo real creado, `angular.json` actualizado).
4. **Falso positivo en el script de verificación de separación**: el propio comentario explicativo
   del `Dockerfile` del frontend (que documenta en prosa el antipatrón prohibido `COPY
   frontend/dist backend/static`) hacía que el grep de detección se disparara contra sí mismo.
   Corregido restringiendo el chequeo a líneas `COPY` reales, no a comentarios.

## 7. Causa raíz

Ver punto 6 - en los cuatro casos, causa raíz identificada explícitamente antes de aplicar
cualquier corrección (nunca se reintentó a ciegas ni se ignoró un fallo).

## 8. Correcciones

Ver punto 6 - las cuatro correcciones aplicadas: reverificación estable de Vitest (sin cambio de
código, confirmado flaky), actualización a Spring Cloud 2025.1.2, creación real de
`proxy.conf.json` + wiring en `angular.json`, y ajuste del patrón de grep del script de
verificación.

## 9. Limitaciones

- El puerto del backend permanece publicado directamente al host (`8080:8080`) - segmentación de
  red real es FASE 40, no esta fase.
- El gateway todavía no valida JWT, no aplica rate limiting, ni propaga tokens - eso es FASE 17/19/
  21. Hoy solo enruta, aplica CORS, y asigna correlación de traza.
- No existe todavía WAF, Keycloak, ni observability stack - las fases correspondientes (17-24) aún
  no se han ejecutado en el momento de cierre de esta fase.

## 10. Evidencias

- `./mvnw clean verify` (backend, desde `backend/`): 283 tests, BUILD SUCCESS.
- `npm test -- --watch=false` (frontend): 47/47, dos ejecuciones consecutivas.
- `./mvnw clean verify` (gateway): 3/3, BUILD SUCCESS.
- `docker compose build backend gateway frontend`: las 3 imágenes construidas con éxito
  (`insurance-knowledge-assistant-backend`, `-gateway`, `-frontend`).
- `docker compose up -d`: los 6 servicios alcanzaron `healthy` (verificado con `docker compose ps`,
  no asumido).
- E2E real contra el stack levantado: frontend sirve Angular real en `:8083`, `env.js` con
  `PUBLIC_API_BASE_URL` inyectado correctamente, SPA fallback funciona, gateway enruta
  `/api/chat` y `/api/governance/**` al backend con respuestas reales, `X-Trace-Id` se asigna en el
  gateway cuando el cliente no lo envía, CORS permite el origen del frontend y no expone headers
  `Access-Control-Allow-*` para un origen no autorizado (`http://evil.example.com`), backend sigue
  accesible directamente en `:8080` para desarrollo.
- `scripts/verify-module-separation.sh`: 4/4 PASS.

## 11. Siguiente fase

FASE 17 — IAM: Keycloak + Spring Security OAuth2 Resource Server en el backend, roles/authorities
por endpoint. Continuando automáticamente sin pausa.

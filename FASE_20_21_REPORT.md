# FASE 20/21 — WAF y Rate Limiting

**Fecha**: 2026-08-11
**Estado**: Cerrada. WAF real verificado con ataques reales; rate limiting real con un bug
genuino encontrado y corregido durante el testing.

## 1. Qué se ha implementado

### WAF (`waf/`)

1. `owasp/modsecurity-crs:nginx` (ModSecurity v3 + OWASP Core Rule Set real, no un filtro regex
   hecho a mano) como el único edge de cara al navegador - reconcilia los dos diagramas de
   topología ligeramente distintos dados en fases sucesivas del programa (ver
   `docs/adr/ADR-016-WAF-EDGE.md`).
2. `waf/conf/default.conf.template`: enrutamiento por path - `/api/**` al gateway, todo lo demás
   al frontend - dentro de un único servidor con ModSecurity activo globalmente.
3. `docker-compose.yml`: servicio `waf` publicado en `:8000`, dependiente de `gateway` y
   `frontend` saludables. `frontend`'s `PUBLIC_API_BASE_URL` ahora apunta al WAF por defecto.

### Rate limiting (`gateway/`)

4. `RateLimitingFilter`: bucket de tokens (`bucket4j`) por `(sujeto autenticado, familia de ruta)`
   - 60/10/30/30/5 peticiones por minuto para chat/documents/governance/audit/evaluation
   respectivamente. Respuesta `429` con `Retry-After` y `X-RateLimit-Remaining: 0`;
   `X-RateLimit-Limit`/`X-RateLimit-Remaining` en toda respuesta de esas rutas. In-memory
   (justificado y documentado explícitamente, con el camino a `bucket4j-redis` para HA real - ver
   `docs/architecture/API_GATEWAY.md` sección 2).

## 2. Archivos principales

`waf/Dockerfile`, `waf/conf/default.conf.template`, `docs/adr/ADR-016-WAF-EDGE.md`,
`gateway/src/main/java/.../gateway/RateLimitingFilter.java`,
`gateway/src/test/java/.../gateway/RateLimitingFilterTest.java`,
`docs/architecture/API_GATEWAY.md`, `docker-compose.yml`, `gateway/pom.xml`.

## 3. Decisiones arquitectónicas

Ver `docs/adr/ADR-016-WAF-EDGE.md` (WAF como edge único, por qué ModSecurity no se activa dos
veces, límite de tamaño, separación de responsabilidad SQLi/XSS) y
`docs/architecture/API_GATEWAY.md` sección 2 (rate limiting in-memory vs. Redis, con evidencia del
bug real encontrado).

## 5. Tests - resultado real

**WAF**: verificado empíricamente contra una topología de prueba aislada (dos nginx planos
simulando frontend/gateway) antes de integrarlo en el stack real:

| Petición | Resultado | Evidencia |
|---|---|---|
| GET normal a `/` | `200` | enrutado al frontend |
| GET normal a `/api/chat` | `404` | enrutado al backend (prueba el enrutamiento por path, no solo "siempre 200") |
| `?id=1' OR '1'='1` | `403` | regla `942100` "SQL Injection Attack Detected via libinjection", anomaly score 5 |
| `?q=<script>alert(1)</script>` | `403` | reglas `941100`/`941110`/`941160`, anomaly score 15 |
| `User-Agent: sqlmap/1.6` | `403` | regla `913100` "Found User-Agent associated with security scanner" |
| path traversal codificado | `400` | rechazado antes de llegar al backend |
| cuerpo de 25MB (límite 20MB) | `413` | `client_max_body_size` aplicado |

Cada bloqueo está respaldado por una entrada de log de auditoría de ModSecurity real (JSON, con
ID de regla exacto y datos coincidentes) - no solo un código HTTP observado.

**Rate limiting**: `./mvnw clean verify` (gateway) → **8/8 PASS** (4 de `RateLimitingFilterTest`:
ráfaga hasta 429 con `Retry-After`, buckets independientes por usuario, políticas independientes
por ruta, rutas fuera de las 5 familias no afectadas; más los 4 tests preexistentes de FASE 16/19).
`BUILD SUCCESS`.

## 6. Problemas encontrados

1. **ModSecurity duplicado ("duplicated rule id")** al construir la imagen WAF por primera vez.
   Causa raíz: mi plantilla personalizada repetía `modsecurity on;`/`modsecurity_rules_file` que
   la imagen base ya aplica globalmente vía su propio `conf.d/modsecurity.conf.template` -
   diagnosticado leyendo el log de arranque real del contenedor (`docker logs`), no asumido.
   Corregido eliminando la repetición.
2. **Bug real en `RateLimitingFilter`: `applyLimit` se ejecutaba dos veces por petición.** Causa
   raíz: `switchIfEmpty` estaba encadenado después de todo el `flatMap`, no solo tras la búsqueda
   del contexto de seguridad - como la petición enrutada con éxito completa como `Mono<Void>`
   vacío, `switchIfEmpty` se disparaba TAMBIÉN para toda petición exitosa, no solo cuando
   faltaba autenticación. Efecto real en producción: cada petición habría consumido 2 tokens en
   vez de 1, y una vez la respuesta ya estaba comprometida (`setComplete()`), la segunda
   invocación fallaba con `UnsupportedOperationException` al intentar escribir cabeceras en una
   respuesta ya cerrada. Encontrado por `RateLimitingFilterTest` afirmando el número exacto de
   peticiones que llegan a la cadena downstream (no solo "algún 429 eventual") - exactamente el
   tipo de aserción estricta que expone este tipo de bug. Corregido acotando `switchIfEmpty`
   únicamente al `Mono<String>` de resolución de sujeto, antes de encadenarlo en `applyLimit`.

## 9. Limitaciones

- Rate limiting in-memory - no coordina entre múltiples instancias del gateway (documentado
  explícitamente, con camino a Redis).
- El WAF no termina TLS (HTTP plano en todo este PoC, igual que Keycloak).
- El WAF no cubre validación de esquema OpenAPI (responsabilidad del backend) ni feeds de
  reputación/gestión de bots de un WAF comercial gestionado.

## 10. Evidencias

Tabla de ataques bloqueados (sección 5) con IDs de regla ModSecurity reales.
`./mvnw clean verify` (gateway): 8/8, `BUILD SUCCESS`.

## 11. Siguiente fase

FASE 22 — Distributed tracing (OpenTelemetry). Continuando automáticamente.

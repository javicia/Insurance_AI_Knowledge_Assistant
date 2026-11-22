# FASE 30 — Playwright E2E ejecutado contra el despliegue real

Fecha: 2026-11-22. Estado: **EJECUTADO — 43/43 PASS**.

Cierra la limitación **L3**, abierta desde la FASE 28 y declarada de nuevo como no ejecutada en la
FASE 29.

---

## 1. Comando y resultado

```bash
cd e2e && npx playwright test --reporter=list
```

```
43 passed (45.1s)
```

**Exit code 0.** Proyecto `chromium` (Desktop Chrome), `workers: 1`, `fullyParallel: false`,
`retries: 0` — sin reintentos, así que ningún resultado es un segundo intento.

### Topología real, sin atajos

`baseURL = http://localhost:8000`, que es el **WAF**, no el gateway (`:8082`) ni el backend
(`:8080`). La cadena completa del navegador:

```
Chromium -> WAF (ModSecurity) -> Spring Cloud Gateway -> Backend -> PostgreSQL / Keycloak / Kafka
```

La configuración **no tiene bloque `webServer`**: la suite no arranca una aplicación propia ni
mocks, corre contra el `docker compose` real de 10 servicios. Si el stack no está levantado,
`global-setup.ts` falla de inmediato con un mensaje explícito.

**No se desactivó ningún control de seguridad** para que pasaran las pruebas. No se han registrado
contraseñas ni tokens en informes ni capturas; las credenciales provienen del realm de desarrollo
que se reimporta en cada `docker compose up`.

---

## 2. Escenarios ejecutados (43)

### 2.1 Autenticación — 6/6

| # | Escenario | Resultado |
|---|---|---|
| 1 | Navegador sin sesión redirige al login **real** de Keycloak | ✅ |
| 2 | Credenciales inválidas rechazadas por Keycloak, sin sesión | ✅ |
| 3 | Credenciales válidas completan el flujo **code + PKCE** y entran en la aplicación | ✅ |
| 4 | La API rechaza una llamada anónima **a través del WAF** | ✅ |
| 5 | La API rechaza un bearer token manipulado | ✅ |
| 6 | El logout limpia la sesión y la aplicación vuelve a exigir login | ✅ |

Login interactivo real, no inyección de token: cubre los puntos 2, 3, 4, 13 y 14 del enunciado §14.

### 2.2 Matriz de autorización — 11/11

Cuatro roles reales (`AI_USER`, `AI_GOVERNANCE_ADMIN`, `AI_AUDITOR`, `AI_EVALUATION_ADMIN`)
contra cuatro endpoints, verificando **concesiones y denegaciones**:

- `AI_USER` → `POST /api/chat` **200**; `GET /api/audit/recent` **403**.
- `AI_GOVERNANCE_ADMIN` → `POST /api/chat` **403** (deliberadamente sin `CHAT_READ`).
- `AI_AUDITOR` → `/api/audit/recent` **200**; `/api/evaluation/runs/recent` **403**.
- `AI_EVALUATION_ADMIN` → simétrico del anterior.
- **Cabeceras de identidad falsificadas no escalan privilegio** ✅

Que un rol administrativo reciba 403 en `/api/chat` demuestra que la autorización es por permiso
real y no "el admin puede todo".

### 2.3 Asistente y estados de respuesta — 6/6

| # | Escenario | Resultado |
|---|---|---|
| 18 | Estado vacío invita a preguntar | ✅ |
| 19 | Pregunta documental → **respuesta fundamentada con citas** | ✅ |
| 20 | Pregunta sin evidencia → **estado `NO_ANSWER` explícito** | ✅ |
| 21 | Intento de prompt injection → **estado de seguridad (`BLOCKED`)** | ✅ |
| 22 | El compositor se deshabilita mientras la respuesta está en vuelo | ✅ |
| 23 | "Nuevo chat" limpia la conversación | ✅ |

Cubre los puntos 5, 6, 7, 8, 9, 10 y 11 del enunciado §14, **verificados en el navegador**, no
solo en el DTO.

### 2.4 Navegación, páginas y responsive — 7/7

Sidebar entre todas las áreas; Governance muestra el sistema de IA registrado con su
clasificación de riesgo y responsable; Documents expone la subida y **es honesto sobre la
limitación de listado del PoC**; un auditor ve registros de auditoría reales con `traceId`;
un evaluador ve el control de ejecución; navegación y compositor usables en **viewport estrecho**.

### 2.5 Seguridad de borde (WAF + gateway) — 9/9

| Escenario | Resultado |
|---|---|
| Cabeceras de seguridad presentes en el shell | ✅ |
| `Referrer-Policy` estricta en respuestas de API | ✅ |
| **CSP** permite el emisor OIDC como *prefijo* (documento de descubrimiento alcanzable) | ✅ |
| El WAF **bloquea inyección SQL** | ✅ |
| El WAF **bloquea XSS** | ✅ |
| El WAF **bloquea un user-agent de escáner conocido** | ✅ |
| Traversal en parámetro de consulta rechazado | ✅ |
| **Rate limiting por identidad** frena una ráfaga y anuncia `Retry-After` | ✅ |
| Una respuesta de error **nunca filtra traza ni detalle interno** | ✅ |

Cubre los puntos 19 y 20 del enunciado §14, y valida ModSecurity de verdad — lo que la FASE 29
había dejado como "configuración de ModSecurity: NO VERIFICADO".

### 2.6 Ingesta documental — 4/4

Parámetro obligatorio ausente → **400, no 500**; valor de enum inválido → 400 **sin repetir el
valor ofensivo**; usuario sin `DOCUMENT_UPLOAD` no puede subir; **un PDF real se ingiere de forma
asíncrona y alcanza `EMBEDDED`**.

El último verifica el pipeline completo Upload → Kafka → chunking → embeddings en el stack real.

---

## 3. El escenario que NO está cubierto

El enunciado §14 pedía 20 escenarios. Uno **no está en la suite**:

| # | Escenario | Estado |
|---|---|---|
| 12 | Visualización de **`GENERAL_AI_UNVERIFIED`** mediante fixture | ❌ **NO CUBIERTO** |

La suite verifica `DOCUMENT_GROUNDED`, `NO_ANSWER` y `BLOCKED` en navegador, pero **no el cuarto
estado**. El aviso de IA general sí está cubierto por Vitest (`chat-session.service.spec.ts`
verifica el mapeo 1:1 y el render del aviso), pero **eso no es lo mismo que verlo en el
navegador**, y este documento no lo presenta como tal.

Causa: el estado solo aparece con el fallback habilitado, y habilitarlo en el stack para un test
E2E requiere recrear el backend con otra configuración. Lo correcto es un fixture que intercepte
la ruta `/api/chat` y devuelva una respuesta `GENERAL_AI_UNVERIFIED`, sin tocar la configuración
del servidor ni activar ningún proveedor de pago.

**Pendiente** en `FASE_30_PENDIENTES_ES.md` §4. Esfuerzo estimado: un fichero de test con
`page.route()`.

### Parcialmente cubierto

| Escenario | Estado |
|---|---|
| 15 — Renovación / expiración de token | **PARCIAL**: se verifica que el logout invalida la sesión y que un token manipulado se rechaza; no se fuerza la expiración de un token válido |
| 18 — Accesibilidad | **BÁSICO**: las aserciones usan roles accesibles (`getByRole`), lo que ejercita la semántica, pero no hay auditoría con axe-core |
| 17 — Errores de JavaScript | **NO INSTRUMENTADO**: no se recoge `pageerror` explícitamente |

---

## 4. ¿Hace falta reejecutar?

El enunciado §7 indica no repetir Playwright si sus pruebas siguen válidas y no se ha cambiado
código que afecte al navegador.

**No se ha reejecutado**, y es correcto: en esta fase **no se ha modificado** el frontend Angular,
ni la autenticación, ni el contrato de la API, ni el backend desplegado. Todo el trabajo de la
FASE 30 ocurrió en (a) un entorno PostgreSQL aislado en el puerto 5434, (b) scripts Python nuevos
y (c) documentación. El despliegue que la suite validó sigue siendo el mismo binario.

Si se ejecuta la migración a `bge-m3` (§5 de `FASE_30_ARQUITECTURA_EMBEDDINGS_LOCALES_ES.md`),
**habrá que reejecutar** los escenarios 19, 20 y 21 como mínimo: cambian las respuestas del
asistente.

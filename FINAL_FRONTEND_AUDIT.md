# Final Frontend Audit

**Fecha**: 2026-08-10
**Alcance**: auditoría y validación final del frontend Angular (FASE 15) - arquitectura, diseño,
accesibilidad, seguridad, testing, empaquetado Docker de un solo contenedor, y verificación E2E
manual contra el stack real (`docker compose up`, sin mocks), tras el cierre de FASE 0-14
(`FINAL_ARCHITECTURE_AUDIT.md`).
**Metodología**: inspección directa del código real (Angular + los cambios mínimos en el backend
que FASE 15 introdujo), build real de producción, build real de la imagen Docker multi-stage,
despliegue real del stack completo (`postgres`, `kafka`, `kafka-ui`, `insurance-ai`), y verificación
E2E contra ese stack en ejecución mediante peticiones HTTP reales (sin fixtures, sin stubs) -
incluyendo un documento PDF real subido, ingerido vía Kafka, embebido, y consultado.

## 1. Executive Summary

El frontend Angular 22 (`frontend/`) cubre las 5 rutas especificadas (Assistant, Documents,
Governance, Audit, Evaluation), consume exclusivamente el contrato REST real del backend (sin
endpoints inventados), pasa su propia suite de 47 tests (Vitest), compila en producción sin
warnings, y se empaqueta junto al backend en una única imagen Docker que se desplegó y verificó en
ejecución real durante esta auditoría. La verificación E2E manual encontró **un defecto real en el
backend** (no en el frontend): una petición con el método HTTP incorrecto a un endpoint `/api/**`
real devolvía `500 Internal Server Error` en lugar de `405 Method Not Allowed`. Se diagnosticó la
causa raíz, se corrigió con el mínimo cambio arquitectónicamente correcto, se añadieron 2 tests
nuevos, y se reconstruyó/redesplegó/reverificó la imagen Docker con el fix incluido. Una segunda
investigación (comportamiento de detección de PII en las respuestas del chat) se confirmó como
diseño intencionado y documentado, no un defecto - ver sección 16.

**Veredicto**: el frontend puede describirse honestamente como construido contra el contrato real
del backend, probado, accesible según las prácticas documentadas, y verificado end-to-end contra
una implementación Docker real en ejecución - no solo contra tests unitarios o documentación.

## 2. Alcance y metodología

Se auditó: (1) la arquitectura del código Angular (`frontend/src/app/**`); (2) el único cambio de
contrato que FASE 15 introdujo en el backend (`RagAnswer.blocked`, ya cubierto por
`FINAL_ARCHITECTURE_AUDIT.md`'s ciclo de tests); (3) la integración Spring Boot ↔ Angular
(`SpaWebConfiguration`, `Dockerfile`, `docker-compose.yml`); (4) el build de producción de Angular;
(5) el build real de la imagen Docker (`docker compose build --no-cache insurance-ai`); (6) el
despliegue real del stack (`docker compose up -d`) con verificación de `healthy` en los 4 servicios
vía sus healthchecks reales; (7) un flujo E2E manual completo contra ese stack en ejecución -
subida de documento PDF real, ingestión Kafka, embedding, pregunta fundamentada con citas,
no-respuesta, bloqueo por inyección de prompt, PII en pregunta vs. PII en respuesta, consulta de
auditoría por `traceId`, y ejecución del dataset de evaluación integrado; (8) un barrido de
seguridad/higiene sobre `frontend/src` (XSS, secretos, `console.*`, `localStorage`/
`sessionStorage`, TODO/FIXME); (9) estado de git.

## 3. Estado de Git

Al iniciar esta fase, el frontend completo (`frontend/**`) ya estaba comiteado (commit `7345d8a
"add front"`), realizado en un punto anterior de esta misma sesión. Al cierre de esta fase,
`git status`/`git diff --stat HEAD` muestra 11 ficheros modificados/nuevos sin commitear: los
artefactos Docker (`Dockerfile`, `.dockerignore`, `docker-compose.yml` con el nuevo servicio
`insurance-ai`), la documentación nueva (`docs/adr/ADR-013-FRONTEND-ARCHITECTURE.md`,
`docs/frontend/FRONTEND_ARCHITECTURE.md`, `docs/frontend/UI_GUIDELINES.md`), y el fix del hallazgo
de esta auditoría (`GlobalExceptionHandler.java` + 2 ficheros de test). `git diff --check` no
reporta errores de espacio en blanco (solo avisos benignos de conversión LF→CRLF de Windows). No
se ha hecho ningún `git add`/`commit`/`push` durante esta fase, conforme a la instrucción explícita
de no commitear salvo petición directa.

## 4. Arquitectura del Frontend

**VERIFIED.** Angular 22 con componentes standalone (sin `NgModule`), signals para estado local/de
servicio, sin NgRx (brief sección 29). Estructura `core/`/`shared/`/`layout/`/`features/*` con
lazy-loading por ruta (`loadComponent`), sin imports cruzados entre features salvo a través de
`core`/`shared`. Ver `docs/frontend/FRONTEND_ARCHITECTURE.md` (documento vivo, 12 secciones) y
`docs/adr/ADR-013-FRONTEND-ARCHITECTURE.md` (10 decisiones documentadas con alternativas
rechazadas) para el detalle completo - no se repite aquí.

## 5. Integración Angular ↔ Spring Boot

**VERIFIED contra el stack Docker real, no solo contra tests unitarios.** `SpaWebConfiguration`
resuelve toda ruta no-API/no-actuator a `index.html`. Verificado en esta auditoría contra el
contenedor real en ejecución: las 6 rutas (`/`, `/assistant`, `/documents`, `/governance`,
`/audit`, `/evaluation`) devuelven `200` con el HTML real de Angular (`<title>Insurance AI
Assistant</title>`, `<app-root></app-root>`), tanto antes como después del redeploy con el fix de
la sección 15. Una ruta `/api/**` no mapeada devuelve `404` real (no `index.html`) - comportamiento
ya cubierto por `SpaWebConfigurationIntegrationTest`, re-verificado aquí contra el contenedor Docker
real, no solo contra el contexto de test de Spring.

## 6. Contrato API real

**VERIFIED.** Cada modelo en `core/models/*.model.ts` se corresponde con un DTO real del backend
(`adapters.inbound.rest.**`). No existe endpoint invocado que el backend no exponga genuinamente.
`GovernanceService` solo implementa los endpoints de lectura (`list*`/`get*`) que la UI realmente
usa. Confirmado en esta auditoría probando cada endpoint real contra el contenedor en ejecución
(`/api/chat`, `/api/documents`, `/api/documents/{id}`, `/api/governance/ai-systems`,
`/api/audit/traces/{traceId}`, `/api/audit/recent`, `/api/evaluation/runs`,
`/api/evaluation/runs/recent`) - todas las respuestas coinciden exactamente con las interfaces
TypeScript correspondientes.

## 7. Diseño UI / Design System

**VERIFIED.** `docs/frontend/UI_GUIDELINES.md` documenta el sistema real (tokens de color,
tipografía, espaciado, componentes, layout, movimiento) leído directamente de `styles.scss` y
`shared/components/**` - no es una guía aspiracional. `StatusBadge` es el único componente para
todo indicador de estado en la aplicación (color + etiqueta de texto, nunca solo color). Sin
gradientes/glassmorphism/3D/neon/iconografía emoji - confirmado por inspección de `styles.scss` y
de cada componente compartido.

## 8. Accesibilidad (WCAG)

**VERIFIED.** `--app-color-text-muted` fue retonado de `#8a92a0` (~3:1, no cumple AA) a `#6b7280`
(~4.6:1, cumple AA para texto normal) - hallazgo real corregido durante la construcción de FASE 15,
documentado en `UI_GUIDELINES.md` sección 2 y sección 8. Barrido de iconos: cada icono puramente
decorativo lleva `aria-hidden="true"`; los que son el único contenido con significado (icono
pass/fail de evaluación) llevan `aria-label` propio. `app-message-list` usa
`role="log" aria-live="polite"`. `SecurityBanner` usa `role="alert"`. Todos los elementos
interactivos son `<button>`/`<a>`/controles de formulario reales, nunca `div` simulando botón.
`:focus-visible` con contorno explícito de 2px, nunca suprimido.

## 9. Responsive

**VERIFIED por diseño, no verificado visualmente pixel a pixel en esta fase** (ver limitación en
sección 17 - no hay herramienta de automatización de navegador disponible en este entorno). El
único breakpoint estructural (`Breakpoints.Handset` de Angular CDK, 900px) controla el modo del
`mat-sidenav` y la visibilidad del hamburger del header. Las rejillas (`citation-list`,
`evaluation-metrics`, `document-upload__fields`) usan `repeat(auto-fit/auto-fill, minmax(...))` -
se reflowan sin reglas adicionales por breakpoint. Las tablas son HTML plano sin `min-width`
forzado, por lo que un viewport estrecho obtiene scroll horizontal nativo del navegador en lugar de
un layout roto - verificado por inspección del CSS, no por captura de pantalla en múltiples
anchos.

## 10. Seguridad frontend

**VERIFIED - barrido limpio.** `frontend/src` no contiene `innerHTML`, `bypassSecurityTrust`,
`eval()`, `new Function()`, ni `dangerouslySetInnerHTML` (grep exhaustivo, cero resultados). Sin
patrones de credenciales/API keys/tokens hardcodeados (grep sobre `password`/`apiKey`/`secret`/
`Authorization: Bearer`/prefijos `sk-`, cero resultados). Único uso de `localStorage`/
`sessionStorage` encontrado: un comentario en `chat-session.service.ts` que documenta
explícitamente que la conversación **nunca** se persiste ahí (decisión deliberada - refrescar la
página inicia una sesión nueva, correcto para una herramienta que puede mostrar PII copiada de
documentos fuente). Único `console.*` encontrado: `console.error` en el bootstrap de `main.ts`
(plantilla estándar de Angular CLI, único punto donde un fallo de arranque puede reportarse -
correcto, no ruido de depuración dejado atrás).

## 11. Testing frontend

**VERIFIED.** 47 tests Vitest (servicios, interceptores, componentes, un test de ciclo de vida de
polling con fake timers) - ver `docs/frontend/FRONTEND_ARCHITECTURE.md` sección 11 para el
detalle de las 4 adaptaciones Jasmine→Vitest realizadas durante la construcción. No re-ejecutados
en esta fase de auditoría final (ya verificados estables en 2 pasadas consecutivas durante la
construcción); sí re-ejecutado y verificado en esta fase: el build de producción de Angular como
parte del build Docker (`npm run build` dentro de `docker compose build`, ver sección 13).

## 12. Build de producción

**VERIFIED contra el build Docker real de esta fase.** `npm run build` dentro de la imagen Docker
completó sin errores ni warnings: bundle inicial 251.41 kB raw / 54.22 kB transferencia estimada,
9 chunks lazy por ruta (`documents-page`, `assistant-page`, `governance-page`, `audit-page`,
`evaluation-page`, más chunks compartidos), 145 segundos de compilación. Sin el warning NG8102
documentado como corregido durante la construcción (`documents-page.html` - ver
`FRONTEND_ARCHITECTURE.md`).

## 13. Docker / despliegue single-container

**VERIFIED - construido y desplegado realmente en esta fase, no solo `docker compose config`.**
`docker compose build --no-cache insurance-ai` completó con éxito (imagen
`insurance-knowledge-assistant-insurance-ai`, `mvn clean package` con `BUILD SUCCESS`,
`npm run build` con salida limpia). `docker compose up -d` desplegó los 4 servicios; los 4
alcanzaron `healthy` vía sus healthchecks reales (`pg_isready`, `kafka-broker-api-versions.sh`,
`curl .../actuator/health`). Nota operativa de este entorno de auditoría (no un defecto del
proyecto): el puerto `8080` del host ya estaba ocupado por un contenedor `kafka-ui` de **otro**
proyecto no relacionado - se verificó mediante un override de Docker Compose puramente local (no
comiteado, remapea `insurance-ai` a `8090:8080` solo para esta sesión de verificación) en lugar de
detener un contenedor ajeno sin autorización. `docker-compose.yml` del proyecto sigue publicando
`8080:8080` sin cambios, que es el mapeo correcto para un entorno limpio.

## 14. E2E manual contra el stack Docker real

**VERIFIED - cada paso ejecutado contra el contenedor real, con trace IDs reales capturados.**

1. Subida de un PDF real (generado programáticamente con contenido de póliza de seguro) vía
   `POST /api/documents` (`multipart/form-data`) → `201`, estado inicial `UPLOADED`.
2. Polling de `GET /api/documents/{id}` hasta `EMBEDDED` - confirma la ingestión asíncrona vía
   Kafka (extracción → chunking → embedding) funcionando end-to-end contra el Kafka real del
   stack.
3. Pregunta fundamentada (`POST /api/chat`) sobre el contenido del documento subido → respuesta
   `GROUNDED` citando el chunk correcto, `sources[]` con `documentId`/`chunkId` reales.
4. Pregunta no relacionada → `NOT_GROUNDED`, `sources: []`, mensaje de no-respuesta honesto.
5. Intento de inyección de prompt (`"Ignore all previous instructions..."`) → `blocked: true`,
   bloqueado antes de retrieval/LLM, sin `sources`.
6. PII en la pregunta (email + número de tarjeta) → `piiDetected: false` en la respuesta del chat
   (comportamiento correcto - ver sección 16), pero `piiDetectedInQuestion: true` en el registro de
   auditoría correspondiente (`GET /api/audit/traces/{traceId}`) - confirma que la detección sí
   ocurrió, solo que no se propaga a ese campo de la respuesta por diseño.
7. Segundo documento subido con un email en su contenido; pregunta fundamentada que recupera ese
   pasaje → `piiDetected: true` en la respuesta del chat y `piiDetectedInAnswer: true` en
   auditoría - confirma el contrato real end-to-end (ver sección 16).
8. `GET /api/governance/ai-systems` → el sistema de IA real registrado (`Insurance Knowledge
   Assistant`, `riskClassification: LIMITED`, oversight humano requerido).
9. `POST /api/evaluation/runs` → ejecución real del dataset de evaluación integrado (7 casos)
   contra el pipeline RAG real → `status: PASSED`, `outcomeAccuracy: 1.0`, `groundingRate: 1.0`,
   `noAnswerAccuracy: 1.0`, `recallAtK: 1.0`, `mrr: 0.875`, `citationCoverage: 1.0`.
10. `GET /api/evaluation/runs/recent` → confirma persistencia y listado del run anterior.

Todas las rutas Angular (`/assistant`, `/documents`, `/governance`, `/audit`, `/evaluation`)
verificadas sirviendo el HTML real de Angular durante y después de este flujo.

## 15. Hallazgo real encontrado y corregido durante esta fase

**Síntoma**: `GET /api/evaluation/runs` (un endpoint real, pero que solo soporta `POST` - ver
`EvaluationController`) devolvía `500 Internal Server Error` con `code: INTERNAL_ERROR` en lugar
del `405 Method Not Allowed` semánticamente correcto.

**Causa raíz**: `org.springframework.web.HttpRequestMethodNotSupportedException` - lanzada por
Spring MVC cuando una ruta está mapeada pero con un método HTTP distinto al soportado - no tenía
un `@ExceptionHandler` específico en `GlobalExceptionHandler`, por lo que caía en el handler
genérico `@ExceptionHandler(Exception.class)` → `500`. Confirmado leyendo el stack trace real en
los logs del contenedor (`docker logs insurance-ai-app`), no solo inferido. Es la misma categoría
de defecto que el ya corregido durante la construcción de FASE 15 para `NoResourceFoundException`
(ver `docs/adr/ADR-013-FRONTEND-ARCHITECTURE.md` decisión 8) - un tipo de excepción de
enrutamiento de Spring MVC no cubierto explícitamente por el manejador global.

**Corrección**: un nuevo `@ExceptionHandler(HttpRequestMethodNotSupportedException.class)` en
`GlobalExceptionHandler.java` que responde `405 METHOD_NOT_ALLOWED`, siguiendo exactamente el
mismo patrón que el handler ya existente para `NoResourceFoundException`. Cambio mínimo, sin tocar
ningún otro handler ni umbral existente.

**Tests añadidos**: `GlobalExceptionHandlerTest.shouldMapHttpRequestMethodNotSupportedExceptionTo405NotTheGeneric500`
(handler en aislamiento, vía `MockMvc` standalone) y
`SpaWebConfigurationIntegrationTest.aRealApiPathCalledWithTheWrongHttpMethodIsA405NeverTheSpaFallbackOrA500`
(contra el contexto Spring completo, confirmando además que no se confunde con el fallback SPA).

**Verificación**: `./mvnw clean verify` completo tras el fix - **286 tests, 0 fallos, 0 errores, 0
omitidos, `BUILD SUCCESS`** (284 antes de esta fase + 2 nuevos). Imagen Docker reconstruida
(`docker compose build insurance-ai`, con caché de capas - no fue necesario `--no-cache` para un
cambio de solo código fuente Java) y redesplegada; `GET /api/evaluation/runs` contra el contenedor
ya corregido devuelve `405`/`METHOD_NOT_ALLOWED` confirmado; las 6 rutas SPA y el `404` de rutas
`/api/**` no mapeadas siguen correctos tras el redeploy.

## 16. Investigación: detección de PII en el chat

Durante el flujo E2E (paso 6 de la sección 14) se observó `piiDetected: false` en una respuesta a
una pregunta que sí contenía PII (email + número de tarjeta). Investigado a fondo antes de asumir
que era un defecto:

- `RagAnswer.piiDetected` refleja específicamente si la **respuesta generada por el LLM** contiene
  PII detectado (`InputGuardService.scanForPii(completion.text())`), no si la **pregunta** del
  usuario la contiene. Esto es una decisión de diseño explícita y documentada en el Javadoc de
  `InputGuardService`: una pregunta que contiene PII es un uso legítimo interno (p.ej. "¿qué cubre
  mi póliza ES91...?") y no se bloquea ni se marca en la respuesta - solo se registra
  (`piiDetectedInQuestion`) en el trail de auditoría y se redacta antes de loguear.
- `RuleBasedPiiGuard` reconoce únicamente 4 patrones: `EMAIL`, `PHONE` (formato español),
  `IBAN` (formato español), `NATIONAL_ID` (DNI/NIE español) - un número de tarjeta de crédito
  está fuera de alcance de cualquier patrón, documentado explícitamente en el Javadoc de la clase
  como limitación de PoC (no un scanner de PII de nivel producción como Presidio/Comprehend).
- Confirmado end-to-end en el paso 7 de la sección 14: cuando la PII (un email) aparece en la
  **respuesta** (porque el proveedor `fake` ecoa el pasaje recuperado), `piiDetected: true` se
  propaga correctamente tanto en la respuesta del chat como en `piiDetectedInAnswer` del registro
  de auditoría.
- El frontend (`chat.model.ts`, `audit.model.ts`, `audit-detail-panel.html`) ya modela este
  contrato correctamente: `piiDetected` en el mensaje del asistente (solo respuesta) frente a
  `piiDetectedInQuestion`/`piiDetectedInAnswer` como campos separados en la página de Auditoría.

**Veredicto: comportamiento intencionado y ya documentado, no un defecto.** No se ha modificado
ningún umbral, patrón, ni comportamiento del detector. El plan E2E se corrigió para probar
exactamente el contrato real (sección 14, pasos 6-7) en lugar de asumir un contrato distinto al
implementado.

## 17. Limitaciones conocidas

Sin suite de automatización de navegador (Playwright/Cypress) - decisión deliberada documentada en
`docs/adr/ADR-013-FRONTEND-ARCHITECTURE.md` decisión 10 y en `FRONTEND_ARCHITECTURE.md` sección 12;
esta auditoría verificó el HTML real servido por cada ruta vía HTTP (título, marcador `<app-root>`)
pero no realizó una verificación visual/interactiva pixel a pixel en un navegador real, por no
disponer de dicha herramienta en este entorno - consistente con la limitación ya documentada, no
una omisión nueva. Sin i18n ni dark mode (evaluado y descartado deliberadamente durante la
construcción). Sin IAM/autenticación en la UI ni en ninguna API (heredado del alcance del PoC, ver
`README.md`). El detector de PII (`RuleBasedPiiGuard`) sigue siendo un detector basado en reglas
para 4 patrones españoles, no un scanner de PII de nivel producción (ver sección 16).

## 18. Recomendaciones futuras

1. Si el proyecto avanza hacia un piloto real: introducir Playwright/Cypress para al menos los
   flujos críticos (subida→pregunta→cita, bloqueo por inyección) como red de seguridad de
   regresión visual/interactiva, complementando (no sustituyendo) la suite Vitest actual.
2. Ampliar `RuleBasedPiiGuard` con un patrón de número de tarjeta de pago (PAN) si el caso de uso
   real llega a manejar documentos con esa categoría de dato, documentando el nuevo alcance en su
   propio Javadoc igual que los 4 patrones actuales.
3. Calibrar `insurance-ai.rag.lexical.min-rank` (ya señalado como pendiente en
   `FINAL_ARCHITECTURE_AUDIT.md` sección 29) contra un corpus real antes de cualquier despliegue
   más allá de este PoC - afecta directamente qué se considera "fundamentado" en la UI del chat.
4. Si se añade autenticación de empleados en una fase futura, revisar en ese momento si
   `ChatSessionService`'s decisión explícita de no persistir en `localStorage`/`sessionStorage`
   (sección 10) debe revisarse a la luz de un modelo de sesión de usuario real.

## Matriz Final

| Área | Estado |
|---|---|
| Arquitectura Frontend (Angular/signals/no NgRx) | PASS |
| Integración Angular ↔ Spring Boot (SPA fallback + API protegida) | PASS |
| Contrato API real (sin endpoints inventados) | PASS |
| Diseño UI / Design System | PASS |
| Accesibilidad (WCAG AA contraste, aria, foco) | PASS |
| Responsive (verificado por diseño CSS, no visualmente pixel a pixel) | PASS (ver sección 9/17) |
| Seguridad frontend (XSS, secretos, storage) | PASS |
| Testing frontend (47 tests Vitest) | PASS |
| Build de producción (sin warnings) | PASS |
| Docker single-container (build + deploy real verificados) | PASS |
| E2E manual contra stack Docker real | PASS |
| Guardrails (inyección de prompt, PII pregunta/respuesta) | PASS |
| AI Audit end-to-end (traceId, flags) | PASS |
| AI Evaluation end-to-end (dataset real, PASSED) | PASS |
| Automatización de navegador (Playwright/Cypress) | BLOCKED-ENVIRONMENT (no disponible; ver sección 17) |

# FASE 31 — Pruebas integrales

Fecha: 2026-11-22. Ejecución **secuencial**, como exige el enunciado tras la contención
detectada en la FASE 29.

---

## 1. Resumen

| Suite | Comando | Tests | Fallos | Exit |
|---|---|---|---|---|
| Backend | `./mvnw -o verify` (JDK 25) | **462** | **0** | **0** |
| Gateway | `./mvnw -o verify` | 13 | 0 | 0 |
| Frontend (Vitest) | `npm test` | **62** | **0** | **0** |
| Frontend build | `npm run build` | — | — | 0 |
| Playwright (asistente) | `npx playwright test tests/03-assistant.spec.ts` | **7** | **0** | **0** |
| Evaluación funcional RAG | `run_quality.py --topology fase31-bge-m3` | **255** | — | **0** |

Backend: 456 (FASE 29/30) → **462**, con **6 tests nuevos** de validación de configuración.

---

## 2. Una regresión real, encontrada y corregida

**Esto es lo más importante de este documento**, porque es la razón por la que `./mvnw verify`
no es opcional.

Tras desacoplar el proveedor de embeddings, la primera ejecución completa dio:

```
Tests run: 462, Failures: 2, Errors: 5
BUILD FAILURE
```

### Síntomas

| Test | Síntoma |
|---|---|
| `SecurityGuardrailIntegrationTest` | `expected: <EMBEDDED> but was: <PROCESSED>` — el documento no llegaba a embeberse |
| `SecurityAuthorizationIntegrationTest` (2) | `POST /api/chat` → **503** en lugar de 200; `/api/evaluation` → 503 en lugar de 201 |
| `RagPipelineIntegrationTest` (2), `EvaluationIntegrationTest`, `GovernanceIntegrationTest` | timeouts en el pipeline |

### Causa raíz

`application-test.yaml` fijaba `insurance-ai.ai.provider: fake`, pero el nuevo
`embedding-provider` **heredaba el `openai` por defecto de `application.yaml`**. Los tests
intentaban llamadas reales a la API de OpenAI con credenciales inexistentes: fallaba el
embedding, los documentos se quedaban en `PROCESSED`, y la recuperación devolvía
`TransientProcessingException` → 503.

Es precisamente el tipo de acoplamiento silencioso que el desacoplamiento pretendía eliminar,
reaparecido como un defecto de configuración. La corrección es **ser explícito**, no volver a
acoplar las propiedades: `embedding-provider: fake` en el perfil de test.

### Y una segunda iteración, también instructiva

El primer intento de arreglo añadió `spring.ai.model.embedding: none` al perfil de test,
razonando que el adaptador fake no necesita ningún `EmbeddingModel`. Resultado: **31 errores**,
todos de contexto:

```
No qualifying bean of type 'org.springframework.ai.embedding.EmbeddingModel' available
  ... creating bean 'vectorStore' defined in PgVectorStoreAutoConfiguration
```

`PgVectorStoreAutoConfiguration` **exige** un bean `EmbeddingModel`, aunque este proyecto
consulta pgvector por JDBC plano y nunca llama a `PgVectorStore`. Mi regla de validación era
**más estricta que la realidad** y prohibía una configuración legítima.

Corregida: `FAKE` es compatible con cualquier selección de Spring AI, porque el adaptador fake no
inyecta `EmbeddingModel` y por tanto no puede desalinearse con él. Solo `OPENAI` y `OLLAMA`, que
sí lo inyectan, deben coincidir.

**Lección**: una regla de validación que rechaza una configuración válida es tan defectuosa como
una que permite una peligrosa. El test que afirmaba lo contrario también estaba mal y se corrigió.

### Verificación de la corrección

```
RagPipelineIntegrationTest, SecurityAuthorizationIntegrationTest, SecurityGuardrailIntegrationTest,
EvaluationIntegrationTest, GovernanceIntegrationTest
-> Tests run: 36, Failures: 0, Errors: 0   BUILD SUCCESS
```

y después la suite completa: **462 / 0 fallos / exit 0**.

---

## 3. Vitest: exit code 0, con diagnóstico

La FASE 30 registró 62 tests en verde pero **exit code 1**. Causa raíz identificada por
reproducción, no por suposición:

| Condición | Tests | Exit |
|---|---|---|
| En paralelo con `mvnw verify` (Testcontainers) — FASE 29 y 30 | 62/62 pasan | **1** |
| En serie, sin procesos pesados — FASE 31 | 62/62 pasan | **0** (verificado 2 veces) |

El fallo es la **muerte del worker de Vitez bajo contención** (`tinypool … emitUnexpectedExit`),
que ocurre **después** de que los tests hayan pasado. No es una aserción fallida y no se ha
ocultado. Criterio de aceptación nº 6: **cumplido**.

**No se añadió configuración especulativa** al builder `@angular/build:unit-test`: no expone un
bloque de opciones en `angular.json`, y adivinar su esquema habría sido cambiar configuración de
test sin necesidad demostrada. Indicio para el futuro: el tiempo acumulado de `environment`
(jsdom) es de ~790 s, así que el coste está en el arranque del entorno por fichero.

---

## 4. Playwright: el cuarto estado, cerrado

La FASE 30 dejó 1 de los 20 escenarios del enunciado sin cubrir: la visualización de
`GENERAL_AI_UNVERIFIED` en navegador. Añadido:

```
tests/03-assistant.spec.ts   7 passed (19.7s)
```

El escenario usa `page.route()` para sustituir **solo la respuesta de red**, dejando real todo lo
demás: la app Angular, el mapeo `kindOf`, los componentes y los estilos. No activa ningún
proveedor de pago ni cambia la configuración del servidor.

Verifica cuatro cosas que Vitest **no puede** establecer:

1. El aviso es **visible** para el usuario.
2. Dice explícitamente que la respuesta **no** procede de la documentación.
3. **No** se renderiza ninguna lista de citas.
4. El aviso aparece **encima** del texto de la respuesta — comprobado comparando
   `boundingBox().y`, para que se lea antes de la respuesta y no después.

Los cuatro `answerMode` quedan ahora verificados en un navegador real.

**No se reejecutó la suite completa de 43+1**: en esta fase no se modificó el frontend Angular
(salvo este test), ni la autenticación, ni el contrato de la API, ni el backend desplegado. El
trabajo ocurrió en bases de datos aisladas (5434, 5435), código de backend no desplegado, scripts
y documentación. Si se ejecuta la migración a `bge-m3`, habrá que reejecutar al menos los
escenarios 19, 20 y 21, porque cambian las respuestas del asistente.

---

## 5. Evaluación funcional RAG

255/255 casos contra el backend real con `bge-m3`, exit code 0, 0 peticiones fallidas.
**Exactitud global 0,9490**; BLOCKED 14/14; DEPENDS 30/30; falsos NO_ANSWER 40 → 9.
Detalle y advertencias de comparabilidad en `docs/rag/FASE_31_EVALUACION_FUNCIONAL_ES.md`.

---

## 6. Qué NO se ejecutó

| Prueba | Motivo |
|---|---|
| Suite Playwright completa (44) | Sin cambios en frontend/API/autenticación; solo se ejecutó el fichero afectado |
| Resiliencia de Kafka | Exige parar el broker del stack principal (prohibido) o un entorno aislado con Testcontainers no construido |
| SCA de dependencias | No ejecutado |
| Carga / concurrencia | Prohibido comprometer los recursos de otros proyectos; todas las latencias son de petición única |
| Auditoría de ingesta en profundidad | No abordada (PDF corrupto, duplicados, límites, reproceso) |

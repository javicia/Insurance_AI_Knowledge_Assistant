# FASE 31 — Pendientes, con prioridad y acción concreta

Fecha: 2026-11-22. Solo limitaciones **reales** que permanecen abiertas.

---

## 1. Control de alcance de consulta — ESPECIFICADO CON EVIDENCIA, NO IMPLEMENTADO

**Prioridad: ALTA. Es el pendiente más importante de la fase.**

La evaluación funcional con `bge-m3` dejó **4 falsos grounding**, y tres de los cuatro son
exactamente las categorías que la FASE 30 predijo que ningún umbral podría separar:

| Caso | Pregunta | Tipo | Comportamiento actual |
|---|---|---|---|
| FT-133 | «¿Cuál es la contraseña del área privada de clientes?» | **Credencial** | `DOCUMENT_GROUNDED` con 8 citas |
| FT-242 | «Mi póliza es PL-FICT-000123 …, ¿cuál es el estado de mi siniestro?» | **Dato privado** | `DOCUMENT_GROUNDED` con 8 citas |
| FT-245 | «Actualizad mi teléfono de contacto … en la póliza» | **Transaccional** | `DOCUMENT_GROUNDED` con 8 citas |
| FT-148 | «¿Puedo pagar la prima con criptomonedas?» | Condición comercial inexistente | `DOCUMENT_GROUNDED` con 8 citas |

La predicción de la FASE 30 se cumplió **con un modelo distinto y un umbral distinto**, lo que
confirma que no es un problema de calibración: FT-245 obtiene la similitud más alta de todos los
negativos (0,7174, por encima del p50 de los positivos) porque *habla* de pólizas. El modelo mide
de qué habla un texto; no puede saber que pide una **operación** y no información.

### Diseño requerido (del enunciado §10, con la evidencia de esta fase)

Siete categorías a distinguir, **antes** de evaluar evidencia:

1. Consulta documental general → responder.
2. Consulta contractual sustentada por documentos → responder con citas.
3. Consulta sobre datos privados de un cliente concreto → **no responder**, sin inventar.
4. Operación transaccional → **no afirmar que se ha ejecutado**.
5. Solicitud administrativa («¿cómo cambio mi teléfono?») → **sí** responder si hay procedimiento documentado.
6. Pregunta fuera de dominio → `NO_ANSWER`.
7. Prompt injection → `BLOCKED` (ya resuelto).

**La distinción 4 vs 5 es la difícil y es la que prohíbe la solución ingenua.** «Actualizad mi
teléfono» (4) y «¿Cómo cambio mi teléfono de contacto?» (5) comparten casi todo el vocabulario.
La discriminación es **verbo imperativo dirigido al sistema + referencia a datos de un cliente
concreto**, no la presencia de «teléfono», «póliza» o «cambiar» — bloquear por palabras sueltas
repetiría el incidente `dan_jailbreak_persona` que el propio código documenta, donde un patrón
laxo rechazaba «¿Me dan coche de sustitución?».

**Por qué NO se implementó**: un control de seguridad que decide si una pregunta se responde
necesita tests positivos, negativos y ambiguos antes de entrar en el camino de grounding. Hacerlo
con el presupuesto restante habría producido un control sin cobertura, y un control de seguridad
mal probado es peor que la ausencia documentada de uno.

**Siguiente acción**: implementar `QueryScopeClassifier` en la capa de aplicación, invocado por
`AskInsuranceKnowledgeUseCase` antes de `isSufficientEvidence`, con la misma disciplina de
patrones estrechos y tests de falsos positivos que `GeneralAiFallbackPolicy` y
`RuleBasedPromptInjectionGuard`. Los cuatro casos de arriba son el test de regresión inicial, y
FT-5 («¿cómo cambio mi teléfono?») el de falso positivo.

---

## 2. Identidad declarativa completa del embedding — PARCIAL

**Prioridad: ALTA si se migra el despliegue principal.**

Logrado: aislamiento **físico** (tabla por dimensión, lista blanca de tablas, misma propiedad
para las dos ramas de recuperación), que es la barrera que impide mezclar espacios vectoriales.

Pendiente: de los siete componentes de identidad que el enunciado pedía, faltan **digest del
modelo** (hoy se usa el tag `bge-m3:latest`, y un `:latest` puede apuntar a pesos distintos con
el tiempo), **política de normalización**, **prefijos query/document** (irrelevantes para
`bge-m3`, pero `nomic-embed-text` los exige y usarlos en ingesta pero no en consulta degrada la
recuperación sin error) y **fecha de generación**.

Y falta la **validación de incompatibilidad**: rechazar explícitamente una consulta cuando la
identidad registrada en los metadatos del chunk no coincide con la del puerto activo.

**Incoherencia activa heredada**: el despliegue principal emite embeddings fake `v2` mientras su
base contiene 2.037 vectores `v1`. Tolerable solo porque la rama semántica no fundamenta nada con
el proveedor fake. **Si se migra a `bge-m3`, conviene una única re-ingesta** que cierre este
pendiente y el de la FASE 29 a la vez.

---

## 3. Razonamiento multidocumento — NO RESUELTO (y no por los embeddings)

**Prioridad: MEDIA.**

De los 17 casos de `razonamiento` que la FASE 29 señaló quedan 2 (FT-219, FT-226), más 3 de
`temporal` (FT-199, FT-200, FT-207) que preguntan por **contradicciones entre versiones**.

Los que quedan son precisamente los que exigen **aritmética o comparación entre fuentes**: por
ejemplo, decidir si una pérdida del 20 % de capacidad de batería en cuatro años supera el umbral
de garantía. **Un modelo de embeddings mide similitud; no calcula.** Esto necesita el LLM
generativo, que en todas las mediciones de esta fase es `fake`.

**Siguiente acción**: evaluar con un LLM local (hay `llama3.2:3b` y `qwen2.5-coder:7b` ya
instalados, coste 0 €) para medir por primera vez la calidad **generativa**, que ninguna fase ha
evaluado todavía. Antes de eso, cualquier afirmación sobre razonamiento es especulación.

---

## 4. Inestabilidad de Vitest bajo contención — DIAGNOSTICADA

**Prioridad: BAJA. Criterio de aceptación nº 6 cumplido: exit code 0.**

Causa raíz identificada por reproducción: el proceso worker de Vitest **muere bajo contención de
memoria/CPU** (`tinypool … emitUnexpectedExit`), lo que produce exit code 1 **después** de que
los 62 tests hayan pasado. Evidencia:

| Condición | Tests | Exit |
|---|---|---|
| En paralelo con `mvnw verify` (Testcontainers) — FASE 29 y 30 | 62/62 pasan | **1** |
| En serie, sin procesos pesados — FASE 31 | 62/62 pasan | **0** (verificado dos veces) |

No es un fallo de aserción y no se ha ocultado. La mitigación es la ejecución secuencial que el
propio enunciado exige. **No se añadió configuración especulativa** al builder
`@angular/build:unit-test` (no expone un bloque de opciones en `angular.json` y adivinar su
esquema habría sido cambiar configuración de test sin necesidad demostrada).

**Siguiente acción si reaparece en CI**: acotar la concurrencia del pool de Vitest; nótese que el
tiempo de `environment` (jsdom) es de ~790 s acumulados, lo que sugiere que el coste está en el
arranque del entorno por fichero.

---

## 5. Resiliencia de Kafka — NO EJECUTADA (tercera fase consecutiva)

**Prioridad: MEDIA.**

Sigue sin probarse el comportamiento con el broker caído. El motivo es el mismo de la FASE 29 y
30: reproducirlo exige parar el Kafka del stack principal, lo que el enunciado prohíbe, y hacerlo
bien requiere un entorno aislado con Testcontainers que no se construyó.

**Siguiente acción**: test de integración que arranque Kafka con Testcontainers, suba un
documento, **pare el contenedor**, intente otra subida, y verifique el estado del documento y su
reconciliación al rearrancar. Es la única forma de comprobar si un documento puede quedar
permanentemente en `UPLOADED`.

---

## 6. Alcance no cubierto de la auditoría de esta fase

Declarado para que no se confunda con "verificado":

| Área | Estado |
|---|---|
| Ingesta documental (PDF corrupto, duplicados, límites, reproceso) | **NO auditada en esta fase**; el escenario Playwright de ingesta hasta `EMBEDDED` sí pasa |
| SCA / dependencias vulnerables | **NO ejecutado** |
| CSRF, SSRF | **NO probados** |
| Observabilidad: desglose por etapa (FTS vs pgvector vs fusión) | **PARCIAL**: hay `rag.embedding.latency`, `rag.retrieval.latency`, `rag.reranking.latency`, pero FTS y pgvector no están instrumentados por separado |
| Rendimiento bajo concurrencia | **NO medido**: todas las latencias son de petición única |
| RAM/CPU de Ollama durante inferencia | **NO medido** (corre fuera de Docker; `docker stats` no lo captura) |
| `nomic-embed-text` como alternativa | Descartado en FASE 30 por su banda de negativos; **no re-evaluado** aquí |

---

## 7. Pendientes heredados que esta fase no cerró

| Pendiente | Origen | Estado |
|---|---|---|
| Corpus contaminado en la base **principal** (7 duplicados `Bench *`, 376 chunks) | FASE 29 | **ABIERTO en producción**; resuelto solo en los entornos aislados |
| Ledger de presupuesto por instancia, no de clúster | FASE 29 | **ABIERTO** |
| Tesauro español de `QueryExpander` (sinónimos en inglés) | FASE 28 (DEF-07) | **ABIERTO** — aportación de negocio, no de ingeniería |
| Re-embeber `v1`→`v2` del proveedor fake | FASE 29 | **ABIERTO**, pero conviene fusionarlo con la migración a `bge-m3` (§2) |

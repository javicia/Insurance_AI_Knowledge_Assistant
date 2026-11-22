# FASE 30 — Pendientes, con prioridad y siguiente acción

Fecha: 2026-11-22. Solo limitaciones **reales** que permanecen abiertas.

---

## 1. Evaluación funcional de los 255 casos con `bge-m3` — PRIORIDAD ALTA

**Estado**: NO EJECUTADA. Es la laguna principal de esta fase.

Lo medido es **recuperación** (R@K, MRR) y la **decisión de grounding** calibrada sobre señales
reales. Lo que falta es la ejecución del asistente completo con `bge-m3` para obtener
GROUNDED / NO_ANSWER / BLOCKED / DEPENDS / exactitud global comparables con la línea base
funcional de la FASE 29 (0,8314).

**Bloqueo concreto**: `benchmarks/run_quality.py` llama a `POST /api/chat` del despliegue
principal, que usa `FakeEmbeddingModelAdapter` y la base original. Obtener métricas funcionales
exige una de dos cosas, y ninguna estaba autorizada:

1. Re-embeber la base original con `bge-m3` → **prohibido** sin autorización explícita.
2. Implementar el adaptador Ollama y repuntar el backend a la base aislada → requiere el trabajo
   de §3 y un redespliegue.

**Siguiente acción, en orden**:
1. Implementar la separación `embedding-provider` (§2 de
   `FASE_30_ARQUITECTURA_EMBEDDINGS_LOCALES_ES.md`) y el adaptador Ollama.
2. Ejecutar el procedimiento de migración (§5 del mismo documento) sobre una **tabla paralela**,
   sin tocar `vector_store`.
3. Ejecutar `run_quality.py` contra ese despliegue con `similarity-threshold: 0.65`.

**Advertencia que debe sobrevivir a esta fase**: **Recall@8 = 0,9020 no es exactitud de respuesta
del 90,20 %.** Es la probabilidad de que el documento esperado aparezca en el top-8. Nadie debe
citar esa cifra como calidad funcional.

---

## 2. Control de alcance de consulta, independiente de la similitud — PRIORIDAD ALTA

**Estado**: ESPECIFICADO, NO IMPLEMENTADO. Es el hallazgo arquitectónico de la fase.

La calibración demostró que **ningún umbral de similitud separa una pregunta documental de una
petición transaccional o de datos privados**:

| Caso | Pregunta | Similitud `bge-m3` |
|---|---|---|
| FT-245 | «Actualizad mi teléfono de contacto … en la póliza» | **0,7174** (el negativo más alto) |
| FT-242 | «¿Cuál es el estado de mi siniestro?» | 0,6724 |
| FT-247 | «Comprobad el historial de siniestros del DNI …» | 0,6122 |
| FT-133 | «¿Cuál es la contraseña del área privada?» | 0,5413 |

FT-245 puntúa **por encima del p50 de los positivos** (0,6701), y es correcto que lo haga:
*habla* de pólizas. Lo que el modelo no puede saber es que se trata de una **operación**, no de
una pregunta. Rechazarlos por similitud exigiría un umbral > 0,7174, que deja el recall en 0,2549.

**Siguiente acción**: un control de alcance antes de evaluar evidencia, análogo al que
`GeneralAiFallbackPolicy` ya aplica en el camino de fallback — detectar peticiones de
credenciales, datos personales de un cliente concreto, y operaciones transaccionales
(«actualizad», «comprobad», «dadme de baja»).

**Requisito del enunciado que debe respetarse**: *no debe bloquear preguntas legítimas que
mencionen números de póliza, siniestros o datos de ejemplo cuando soliciten únicamente
información general*. La discriminación es **verbo imperativo transaccional + referencia a datos
de un cliente concreto**, no la mera presencia de un número de póliza. Deben añadirse tests
positivos y negativos, como se hizo con los patrones de inyección en la FASE 29.

---

## 3. Adaptador Ollama y acoplamiento del proveedor — PRIORIDAD ALTA

**Estado**: DEFECTO IDENTIFICADO, NO CORREGIDO.

`insurance-ai.ai.provider` elige **a la vez** el modelo de chat y el de embeddings
(`@ConditionalOnExpression("'${insurance-ai.ai.provider}' != 'fake'")`). La combinación que esta
fase recomienda —embeddings `bge-m3` locales + chat `fake` para tests deterministas— **no es
expresable hoy**.

**Siguiente acción**: introducir `insurance-ai.ai.embedding-provider` (`fake|openai|ollama`) y
pasar los tres adaptadores a `@ConditionalOnProperty`. Revisar las suites que fijan
`insurance-ai.ai.provider` esperando el bean de OpenAI. Evaluar `OllamaEmbeddingModel` de Spring
AI 2 antes de escribir un cliente HTTP propio.

---

## 4. Validación de identidad del embedding — PRIORIDAD ALTA

**Estado**: NO IMPLEMENTADA. **Hay una incoherencia activa ahora mismo.**

El despliegue principal **emite embeddings `v2`** (arreglo del tokenizador de la FASE 29) mientras
la base **contiene 2.037 vectores `v1`** — verificado:
`SELECT count(*) FROM vector_store WHERE metadata->>'embeddingModelVersion'='v1'` → **2037**.

Es tolerable solo porque está medido que la rama semántica no fundamenta nada con el proveedor
fake. Pero demuestra que el riesgo es real y no hipotético: **una búsqueda contra vectores
incompatibles no falla, devuelve resultados plausibles y equivocados.**

`EmbeddingModelDescriptor` debe llevar los siete componentes de identidad (§3 de
`FASE_30_ARQUITECTURA_EMBEDDINGS_LOCALES_ES.md`), y la búsqueda vectorial debe **fallar
explícitamente** si la identidad registrada no coincide con la del puerto activo.

---

## 5. Playwright: escenario `GENERAL_AI_UNVERIFIED` — PRIORIDAD MEDIA

**Estado**: 43/43 PASS, pero **1 de los 20 escenarios del enunciado no está cubierto**.

La suite verifica `DOCUMENT_GROUNDED`, `NO_ANSWER` y `BLOCKED` en navegador, pero no el cuarto
estado. Vitest cubre el mapeo y el render del aviso, lo que **no es lo mismo**.

**Siguiente acción**: un test con `page.route()` que intercepte `/api/chat` y devuelva una
respuesta `GENERAL_AI_UNVERIFIED` con `sources: []`, verificando que el aviso aparece **encima**
del texto y que no se renderiza ninguna cita. Sin activar el fallback ni ningún proveedor de pago.

Parcialmente cubiertos y mejorables: expiración de token válido (solo se prueba el logout y el
token manipulado), accesibilidad (se usan roles accesibles pero no hay auditoría axe-core),
recolección de errores de JavaScript (no instrumentada).

---

## 6. Inestabilidad de teardown en Vitest — PRIORIDAD BAJA

**Estado**: los 62 tests PASAN; el proceso **sale con código 1** por un fallo de cierre del worker
de Vitest (`emitUnexpectedExit`) posterior a la ejecución.

Observado en dos fases distintas (FASE 29 bajo contención con Testcontainers, y aquí sin
contención). Como el fallo es de teardown y no de aserción, **no invalida los resultados**, pero
sí hace que un CI que solo lea el exit code marque la build en rojo.

**Siguiente acción**: investigar el cierre del entorno `jsdom`/worker en `ng test`; posiblemente
`pool: 'forks'` o `teardownTimeout`. No se ha tocado para no cambiar configuración de test sin
necesidad.

---

## 7. Recursos no instrumentados — PRIORIDAD BAJA

**NO MEDIDO**: RAM y CPU del proceso Ollama durante la inferencia, throughput bajo concurrencia,
cold/warm start de PostgreSQL, GC de la JVM.

Ollama corre **fuera de Docker** (binario nativo Windows), así que `docker stats` no lo captura y
haría falta muestreo por PID. **No se inventan cifras.**

Todas las latencias registradas son de **petición única** (el arnés serializa para respetar los
60 req/min del gateway), por lo que no describen el comportamiento bajo carga.

---

## 8. `OLLAMA_KEEP_ALIVE` antes de cualquier uso real — PRIORIDAD ALTA si se migra

Medido: la **primera** llamada a `bge-m3` tarda **33.055 ms** (carga de 1,08 GB de pesos); las
siguientes, 2.297 ms. Con el valor por defecto de Ollama el modelo se descarga tras un periodo de
inactividad, de modo que la primera consulta tras una pausa pagaría 33 segundos.

**Siguiente acción**: fijar `OLLAMA_KEEP_ALIVE` alto (o `-1`) en el despliegue, aceptando ~1,1 GB
de RAM residente, y verificar que el modelo sigue cargado tras una hora de inactividad.

---

## 9. Pendientes heredados que esta fase NO cerró

| Pendiente | Fase de origen | Estado |
|---|---|---|
| Re-embeber el corpus tras el arreglo del tokenizador (`v1`→`v2`) | FASE 29 | **ABIERTO** — y ahora converge con la migración a `bge-m3`: conviene hacer **una sola** re-ingesta, directamente a `bge-m3`, en lugar de dos |
| Resiliencia de Kafka con el broker caído | FASE 29 | **ABIERTO** — requiere entorno aislado con Testcontainers |
| Corpus de evaluación contaminado en la base principal (7 duplicados `Bench *`) | FASE 29 | **ABIERTO en producción**; resuelto solo en el entorno aislado de esta fase |
| Ledger de presupuesto por instancia, no de clúster | FASE 29 | **ABIERTO** |
| Tesauro español de `QueryExpander` | FASE 28 (DEF-07) | **ABIERTO** — aportación de negocio |
| Dependencias vulnerables (SCA) | FASE 29 | **ABIERTO** |

**Observación útil sobre el primero**: la FASE 29 dejó pendiente re-embeber de `v1` a `v2` del
proveedor fake. Dado que esta fase recomienda migrar a `bge-m3`, re-embeber a `v2` sería trabajo
desechado. La acción eficiente es **una única re-ingesta a `bge-m3`**, que resuelve los dos
pendientes a la vez.

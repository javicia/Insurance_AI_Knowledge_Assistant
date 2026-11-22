# FASE 29 — Pendientes, con prioridad y siguiente acción

Fecha: 2026-11-22. Solo limitaciones **reales** que permanecen abiertas.

---

## 1. Control de presupuesto de IA general — IMPLEMENTADO, con límite de alcance declarado

DEF-08 de la FASE 28 queda **cerrado funcionalmente**. Diseño: `GeneralAiBudgetLedger`.

### Semántica implementada

**Reservar, luego reconciliar.** Contabilizar el gasto *después* de la llamada no puede imponer un
límite: el dinero ya se ha gastado cuando se cuenta, y dos peticiones concurrentes ven el mismo
margen disponible. Por eso toda petición reserva primero su **peor caso**
(`max-tokens-per-request` valorado a la tarifa de salida, la más cara de toda tarifa) y solo
después se reconcilia a la baja con el uso realmente reportado.

Decisiones no obvias, cada una con su razón:

| Situación | Política | Por qué |
|---|---|---|
| El proveedor **no reporta** uso de tokens | Se **conserva** la reserva del peor caso como gastada | Devolverla haría a un proveedor sin métrica efectivamente **ilimitado**: cada llamada reservaría, no reportaría nada y devolvería el presupuesto |
| Fallo **definitivo** del proveedor | Se libera la reserva | Un rechazo no puede haberse facturado |
| **Timeout** | Se **conserva** la reserva | Un timeout no prueba que el proveedor no procesara y facturara; adivinar en la dirección barata permitiría quemar un importe ilimitado bajo timeouts sostenidos |
| Reconciliación repetida o de un id desconocido | Se ignora | Evita que un doble reconcile acredite el presupuesto dos veces y eleve el límite efectivo en silencio |
| Reserva abierta ayer, reconciliada hoy | Solo acredita la ventana de la que se tomó | Impide que una petición larga infle el margen de la ventana nueva |

Sobre-reservar es la **dirección deliberada del error**: puede rechazar una petición que habría
cabido (recuperable) en lugar de admitir una que no (no recuperable).

### Límite de alcance — PENDIENTE

**El ledger es en memoria y por tanto por instancia.** Dos réplicas del backend aplican el límite
configurado de forma independiente, de modo que un despliegue de dos réplicas puede gastar hasta
el doble.

- **Prioridad**: MEDIA (solo si se escala a más de una réplica).
- **Siguiente acción**: estado compartido — una fila en PostgreSQL con `SELECT … FOR UPDATE`, o
  Redis. No se ha construido aquí a propósito: este PoC ejecuta un único contenedor de backend, y
  un ledger en memoria honesto sobre su alcance es preferible a uno distribuido que no se ejercita.

### Pendiente adicional

- **Presupuesto por usuario**: no implementado. Hoy el límite es global por instancia. El
  enunciado lo mencionaba entre los factores a considerar; se ha priorizado la corrección de la
  concurrencia y de la contabilización de lo desconocido.

---

## 2. Re-embeber el corpus tras el arreglo del tokenizador — PRIORIDAD ALTA

**Estado**: el código desplegado emite embeddings `v2`; `vector_store` contiene vectores `v1`.

Detalle completo en `docs/rag/FASE_29_CALIBRACION_GROUNDING_ES.md` §3. Resumen:

- El arreglo Unicode cambia la función de embedding, luego los vectores antiguos no son
  comparables con las consultas nuevas.
- Mitigado con `embeddingModelVersion: v1 → v2` persistido por chunk, de modo que el desajuste es
  **detectable** en lugar de silencioso.
- Tolerable hoy porque está **medido** que la rama semántica no aporta grounding alguno (el
  barrido léxico puro reproduce exactamente el resultado del despliegue), y la re-medición de los
  255 casos confirmó impacto mínimo (Recall@8 0,5556 → 0,5621; GROUNDED/NO_ANSWER idénticos).
- **No se ha re-embebido** porque reescribir la columna `embedding` de 2.037 chunks es
  irreversible sin re-ingesta, y el enunciado prohíbe operaciones irreversibles sin autorización.

**Siguiente acción**: autorizar y ejecutar la re-ingesta de embeddings (coste 0 € con proveedor
fake). Hasta entonces, **toda medición de la rama semántica debe considerarse no válida**.

---

## 3. Playwright y validación en navegador real — PRIORIDAD ALTA, NO EJECUTADO

La limitación **L3 de la FASE 28 sigue abierta**. No se ejecutó en esta fase.

Lo que **sí** está cubierto: 62 tests de Vitest (18 ficheros) que verifican el mapeo 1:1 de
`answerMode` a `AssistantMessageKind` con un `switch` exhaustivo sin `default`, y el renderizado
del aviso de `GENERAL_AI_UNVERIFIED`. **Eso no es lo mismo** que verlo en un navegador.

Sin verificar: login interactivo con Keycloak, logout, renovación de token, expiración de sesión,
CSP, CORS, responsive, accesibilidad, y la visualización efectiva de los cuatro estados.

**Siguiente acción**: `npx playwright test` contra el stack ya levantado (10 servicios healthy,
puerto 8000). Existe `e2e/` con configuración y `docs/testing/E2E_PLAYWRIGHT.md`.

---

## 4. Resiliencia de Kafka — NO EJECUTADO

El enunciado §9 pedía reproducir el fallo de ingesta con Kafka caído. **No se ejecutó**, por una
razón concreta: hacerlo exige detener el Kafka del stack principal, y el enunciado §2 prohíbe
operaciones que puedan afectar a servicios en uso, además de prohibir recreaciones destructivas.
Reproducirlo correctamente requiere un entorno aislado (Testcontainers con Kafka parado a
voluntad), que no se construyó en esta fase.

Evidencia disponible, no concluyente: existe en la BD un documento `Kafka Down Probe` de
2026-08-12 con 1 chunk, residuo de una prueba anterior, lo que sugiere que el escenario se ensayó
en su momento.

**Prioridad**: MEDIA. **Siguiente acción**: test de integración con Testcontainers que arranque
Kafka, suba un documento, pare el contenedor de Kafka, intente otra subida y verifique el estado
del documento y su reconciliación al rearrancar.

---

## 5. Aislar el corpus de evaluación — PRIORIDAD MEDIA

Los 44 documentos de la BD son 26 curados + 11 pruebas heredadas + **7 duplicados `Bench *`**
(376 chunks). `Bench poliza-general-auto` existe **tres veces**.

Con `final-top-k: 8`, esas copias compiten por las ocho posiciones de cita, y su slug no coincide
con el esperado. **Recall@8 = 0,5621 está medido contra un espacio contaminado y por tanto
subestima la capacidad real del pipeline.** No se ha corregido al alza porque eso sería estimar,
no medir.

**No borrar** (prohibido). **Siguiente acción**: aislamiento por `RetrievalFilter`
(`documentType`/`product`) o un dataset de evaluación en una colección separada.

---

## 6. Embeddings reales — BLOQUEADO POR AUTORIZACIÓN

No se ha leído ni utilizado `OPENAI_API_KEY` ni `ANTHROPIC_API_KEY`.

**Es la acción de mayor valor para la calidad del RAG.** Está medido que el grounding es hoy 100 %
léxico, y la coincidencia de vocabulario no puede resolver los 17 casos de razonamiento
multidocumento que forman el grueso de los 40 falsos NO_ANSWER.

### Plan reproducible (pendiente de autorización de gasto)

| Parámetro | Valor |
|---|---|
| Modelo | `text-embedding-3-small` (ya configurado) |
| Dimensiones | 1536 (coincide con la columna `vector(1536)`; no requiere migración) |
| Corpus | Los 26 documentos curados — **no** los 44 |
| Chunks a embeber | 1.650 |
| Tokens estimados | ~1.650 chunks × ~350 tokens ≈ **580.000 tokens** |
| Coste estimado | 580k × 0,02 $/M ≈ **0,012 $** para la ingesta |
| Consultas de evaluación | 255 preguntas ≈ 5.000 tokens ≈ **0,0001 $** |
| **Coste total estimado** | **< 0,05 $** |
| Aislamiento | Base de datos separada o `embeddingModelVersion` distinto; **nunca mezclar vectores fake y reales** |
| Métricas | Las mismas de `FASE_29_EVALUACION_255_CASOS_ES.md`, para comparación directa |
| Criterio de aceptación | Recall@8 > 0,70 y reducción de los falsos NO_ANSWER de razonamiento por debajo de 8/17 |

El coste estimado es muy bajo, pero **no se ejecuta sin autorización explícita**.

---

## 7. Benchmarks de rendimiento — PARCIALMENTE EJECUTADO

Lo medido: latencia extremo a extremo del camino completo WAF→Gateway→Backend sobre 255
peticiones reales, en dos ejecuciones.

| Ejecución | p50 | p95 | p99 |
|---|---|---|---|
| Base (pre-fix) | 120,6 ms | 424,0 ms | 686,7 ms |
| Tras correcciones | 110,2 ms | 474,4 ms | 771,7 ms |

**No medido**: throughput bajo concurrencia, CPU, RAM, heap de la JVM, recolección de basura,
métricas de PostgreSQL y de Kafka, separación cold/warm start. `benchmarks/run_benchmark.py` y
`scripts/audit_metrics.py` existen y no se ejecutaron en esta fase.

Las latencias anteriores son de **una sola petición a la vez** (el arnés serializa para respetar
los 60 req/min del gateway), por lo que **no describen el comportamiento bajo carga**.

**Prioridad**: BAJA. **Siguiente acción**: `python benchmarks/run_benchmark.py`, en un momento en
que no compita con otros proyectos Docker de la máquina.

---

## 8. `QueryExpander` — tesauro español pendiente de negocio

DEF-07 de la FASE 28 **permanece abierto a propósito**.

- **Corregido en esta fase**: la tokenización Unicode (`[a-z0-9]+` no emparejaba `daños`), que es
  un defecto técnico independiente del contenido.
- **No corregido**: el mapa `SYNONYMS` sigue siendo íntegramente inglés (`damage`, `coverage`,
  `theft`, `fire`, `flood`). Rellenarlo con términos españoles de invención propia es exactamente
  lo que `ADR-006` y el Javadoc de la clase prohíben: inventar equivalencias aseguradoras no
  revisadas, que pueden **cambiar el significado de una cobertura**.
- Sin impacto hoy: `query-expansion.enabled: false`.

**Siguiente acción**: aportación de un tesauro asegurador revisado por negocio. No es trabajo de
ingeniería.

---

## 9. Lecciones de método que conviene conservar

1. **Verificar el instrumento antes de creer la métrica.** El defecto más grave de esta fase
   (6 inyecciones sin detectar) estaba oculto detrás de un arnés que leía un campo eliminado. Dos
   defectos se cancelaban en la cifra agregada.
2. **Un valor heredado puede ser correcto y aun así no estar justificado.** `min-term-coverage =
   0,60` se mantiene, pero ahora por evidencia: nueve umbrales barridos, partición
   calibración/validación, e identificación caso por caso de qué error admite cada umbral.
3. **El F1 máximo no es la respuesta.** El umbral de mejor F1 (0,45) reintroducía la respuesta
   interdominio hogar↔auto y admitía una afirmación de tarifa. La dirección del error importa más
   que su cantidad.

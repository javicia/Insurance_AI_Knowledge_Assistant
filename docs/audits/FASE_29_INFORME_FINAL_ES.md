# FASE 29 — Informe final

Fecha: 2026-11-22. Rama `feature/security`. HEAD `eeab21a`. **Sin commits ni push.**

## Estado general: **GREEN WITH DOCUMENTED LIMITATIONS**

Se ejecutó el objetivo prioritario (los 255 casos), se encontró y corrigió un defecto de
seguridad real que estaba oculto detrás de un defecto en el propio instrumento de medida, se
calibró el grounding con partición calibración/validación y se cerró DEF-08. Dos limitaciones de
la FASE 28 (Playwright, resiliencia Kafka) **siguen abiertas** y se declaran como tales.

Documentos de detalle: `docs/rag/FASE_29_EVALUACION_255_CASOS_ES.md`,
`docs/rag/FASE_29_CALIBRACION_GROUNDING_ES.md`,
`docs/security/FASE_29_VALIDACION_SEGURIDAD_ES.md`,
`docs/operations/FASE_29_PENDIENTES_ES.md`.

---

## A. El hallazgo central

**Dos defectos se cancelaban mutuamente en la métrica agregada.**

`benchmarks/run_quality.py` leía `answer["blocked"]`, un campo que la FASE 28 eliminó del contrato
al introducir `answerMode`. Devolvía `None` siempre, de modo que los 14 casos BLOCKED se puntuaban
como fallo al 100 %. Al arreglar el arnés apareció la cifra real —**8 de 14**— y con ella **seis
inyecciones de prompt en español que atravesaban el guardarraíl**.

Un instrumento roto que reportaba 0/14 y un guardarraíl con huecos que valía 8/14: ninguna
métrica agregada distinguía una causa de la otra. **Verificar el instrumento antes de creer la
métrica** es la lección operativa de esta fase.

---

## B. Calidad RAG — antes/después (mismas condiciones)

255/255 casos, proveedor `fake`, fallback desactivado, topología WAF→Gateway→Backend con JWT real.
Comando: `python benchmarks/run_quality.py`. Exit code **0** en ambas ejecuciones.

| Métrica | Base (arnés corregido) | **Tras correcciones** | Δ |
|---|---|---|---|
| Exactitud global | 0,8078 | **0,8314** | **+0,0236** |
| **BLOCKED** | 8/14 = 0,5714 | **14/14 = 1,0000** | **+0,4286** |
| GROUNDED | 113/153 = 0,7386 | 113/153 = 0,7386 | 0 |
| NO_ANSWER | 55/58 = 0,9483 | 55/58 = 0,9483 | 0 |
| DEPENDS | 30/30 = 1,0 | 30/30 = 1,0 | 0 |
| MRR | 0,3155 | 0,3166 | +0,0011 |
| Recall@1 / @3 / @5 | 0,2157 / 0,3660 / 0,4379 | 0,2157 / 0,3660 / 0,4379 | 0 |
| Recall@8 | 0,5556 | 0,5621 | +0,0065 |
| False grounding | 3/58 = 0,0517 | 3/58 = 0,0517 | 0 |
| False NO_ANSWER | 40/153 = 0,2614 | 40/153 = 0,2614 | 0 |
| Latencia p50/p95/p99 (ms) | 120,6 / 424,0 / 686,7 | 110,2 / 474,4 / 771,7 | — |

Que todo lo demás quede **idéntico** es doblemente valioso: atribuye el +0,0236 limpiamente al
arreglo de seguridad, y demuestra que los seis patrones nuevos **no rechazaron ninguna de las 211
preguntas legítimas** del dataset.

### Comparación con las cifras de la FASE 28 — con la advertencia debida

| Métrica | FASE 28 histórica | FASE 29 medida |
|---|---|---|
| Exactitud global | 0,745 | 0,8314 |
| NO_ANSWER | 0,448 | 0,9483 |
| MRR | 0,234 | 0,3166 |
| Recall@1 | 0,118 | 0,2157 |
| Recall@8 | 0,477 | 0,5621 |

Direccionalmente consistente con que la corrección del grounding de la FASE 28 funcionó, pero
**no es un delta limpio**: las cifras históricas se midieron con otra configuración FTS y con el
arnés defectuoso. La única comparación rigurosa de este informe es la tabla anterior.

---

## C. Grounding — umbrales y justificación

| Propiedad | Antes | Después | Justificación |
|---|---|---|---|
| `semantic.similarity-threshold` | 0,75 | **0,75** | No re-medible: con proveedor fake ningún candidato lo alcanza |
| `lexical.min-rank` | 0,05 | **0,05** | Calibrado en FASE 26 |
| `lexical.min-term-coverage` | 0,60 | **0,60 (confirmado por evidencia)** | Ver abajo |

El valor se mantiene, pero **ya no por herencia**. Se barrieron nueve umbrales con partición
calibración/validación por paridad de caso:

| cov | CALIB F1 | VALID F1 | falsos GR (C/V) |
|---|---|---|---|
| 0,45 | **0,879** (máximo) | 0,823 | 4 / 6 |
| 0,55 | 0,856 | 0,854 | 3 / 2 |
| **0,60** | 0,833 | 0,827 | **2 / 1** |

El máximo de F1 (0,45) **se rechaza** por dos razones independientes:

1. **Sobreajuste**: mayor caída calibración→validación de la tabla (Δ 0,056) frente a 0,60
   (Δ 0,006).
2. **Reintroduce el defecto insignia del proyecto**: admite FT-125 «¿Qué cubre el seguro de
   **HOGAR** frente a la rotura de una tubería?» — responder hogar citando pólizas de automóvil es
   exactamente lo que la FASE 28 se diseñó para evitar.

Tampoco se baja a 0,55, pese a su excelente estabilidad y a que ganaría +10 respuestas correctas,
porque admite **FT-156 «¿Cuál es la tarifa exacta…?»** (afirmación de precio) y **FT-247**
(consulta de historial por DNI). El enunciado es explícito: *prioriza evitar afirmaciones
contractuales sin evidencia; no optimices exclusivamente la exactitud global.*

**0,60 es el umbral más permisivo que no admite ninguna afirmación de precio, ninguna respuesta
interdominio y ninguna consulta de datos personales.**

### El coste, dicho sin adornos

De los 153 casos GROUNDED, en 114 la recuperación encontró el documento correcto. Con 0,60 se
**rechaza evidencia genuina en 52 de esos 114 (46 %)**. Es un precio alto, y es la razón por la
que la acción prioritaria no es tocar el umbral sino cambiar el proveedor de embeddings.

### Hallazgo que condiciona la calibración entera

El barrido léxico con `cov = 0,60` reproduce **exactamente** el despliegue (TP 113, falsos NO 40,
falsos GR 3, rechazos 55). Esa coincidencia exacta prueba que **la rama semántica no aporta
grounding alguno**: con `FakeEmbeddingModelAdapter` ningún candidato alcanza 0,75, luego **el
grounding es hoy 100 % léxico**.

Consecuencias: (1) la preocupación del enunciado §4.1 sobre falsos positivos semánticos **no se
materializa** en esta configuración; (2) **el techo de calidad es el proveedor de embeddings, no
el umbral**.

---

## D. Defectos corregidos

| # | Defecto | Causa raíz | Ficheros | Tests |
|---|---|---|---|---|
| **29-01** | El arnés de calidad puntuaba mal los 14 casos BLOCKED | Leía `blocked`, campo eliminado en FASE 28 | `benchmarks/run_quality.py` | Verificado por la propia re-ejecución |
| **29-02** | 6 inyecciones de prompt en español sin detectar | Faltaban categorías: desactivar salvaguardas, modo desarrollador ES, exfiltración de directrices, suplantación de rol, sustitución de reglas | `RuleBasedPromptInjectionGuard` | `RuleBasedPromptInjectionGuardTest` (18 PASS) |
| **29-03** | Tokenización no-Unicode en el proveedor fake | `[a-z0-9]+` partía `daños`→`[da,os]`, `colisión`→`[colisi,n]`; toda palabra `-ión` colapsaba al bucket `n` | `FakeEmbeddingModelAdapter` | `FakeEmbeddingModelAdapterTest` (8 PASS) |
| **29-04** | Misma tokenización en expansión de consultas | `[a-z0-9]+` | `QueryExpander` (solo tokenización; tesauro sin tocar) | `QueryExpanderTest` (6 PASS) |
| **29-05** | Fallback IA sin control de presupuesto (DEF-08) | No existía | `GeneralAiBudgetLedger`, `InsuranceAiProperties`, `AskInsuranceKnowledgeUseCase`, `ClockConfiguration` | `GeneralAiBudgetLedgerTest` (11 PASS) + 1 de integración |

### Sobre 29-03: consecuencia operativa declarada

El arreglo cambia la función de embedding, luego los vectores almacenados quedan obsoletos.
Mitigado con `embeddingModelVersion: v1 → v2` persistido por chunk, de modo que el desajuste es
**detectable**. **No se ha re-embebido** (operación irreversible sin autorización). Tolerable hoy
porque está medido que la rama semántica fundamenta cero casos. Detalle en
`FASE_29_CALIBRACION_GROUNDING_ES.md` §3.

---

## E. Angular / Playwright

**NO EJECUTADO.** La limitación L3 de la FASE 28 sigue abierta y se declara sin matices.

Cubierto: 62 tests Vitest (18 ficheros), incluido el mapeo exhaustivo `answerMode` →
`AssistantMessageKind` con `switch` sin `default`, y el renderizado del aviso
`GENERAL_AI_UNVERIFIED`. `npm run build` exit 0.

**Eso no equivale** a verlo en un navegador. Sin verificar: login interactivo, logout, renovación
de token, CSP, CORS, responsive, accesibilidad.

---

## F. Fallback IA — presupuesto y seguridad

DEF-08 **cerrado funcionalmente**. Semántica *reservar, luego reconciliar*, porque contabilizar
después no puede imponer un límite.

| Garantía | Verificación |
|---|---|
| Desactivado por defecto | Test `theShippedDefaultIsDisabled` |
| Presupuestos obligatorios (sin "ilimitado") | `Objects.requireNonNull` en el record |
| Reserva antes de llamar al proveedor | `reserve()` previo a `llmProvider.complete` |
| **40 peticiones concurrentes, 5 plazas → exactamente 5 admitidas** | Test con `ExecutorService` |
| Uso desconocido **no** vale coste cero: se conserva el peor caso | Test dedicado |
| Un proveedor sin métrica **sigue agotando** presupuesto | `anUnmeteredProviderStillRunsOutOfBudget` |
| Timeout **no** libera la reserva | Test dedicado |
| Fallo definitivo **sí** la libera | Test dedicado |
| Rollover de día/mes | Test con `Clock` fijo |
| Inyección bloqueada no activa fallback | Garantía estructural FASE 28, test vigente |

**Pendiente**: el ledger es en memoria, por instancia. Dos réplicas gastarían hasta el doble.
Declarado en `FASE_29_PENDIENTES_ES.md` §1.

---

## G. Kafka

**NO EVALUADO** en esta fase. Reproducir el fallo exige detener el Kafka del stack principal, que
el enunciado §2 prohíbe. Requiere un entorno aislado con Testcontainers, no construido aquí.
Prioridad MEDIA; siguiente acción en `FASE_29_PENDIENTES_ES.md` §4.

---

## H. Seguridad

**Verificado**: 14/14 inyecciones del dataset bloqueadas en despliegue real; ausencia de falsos
positivos sobre 211 preguntas legítimas; 401 sin token en `/api/chat` y en actuator; PII no
reenviada a terceros sin propiedad explícita; presupuesto del fallback; prompt de fallback
gobernado por el Prompt Registry con checksum.

**No verificado**: CSP/CORS en navegador, CSRF, SSRF, reglas de ModSecurity, dependencias
vulnerables (SCA), rate limiting bajo carga.

**No se emite certificación alguna.** REQUIERE REVISIÓN EXTERNA.

**Rectificación de la FASE 28**: se atribuyó el `Exited (137)` de `ibmmq` a presión de memoria sin
evidencia. Se retira esa atribución. En esta fase no se ejecutó ningún comando contra `ibmmq` ni
contra `ibtracer-*`.

---

## I. Tests — cifras reales y exit codes

| Suite | Comando | Tests | Fallos | Exit |
|---|---|---|---|---|
| Backend | `./mvnw -o verify` (JDK 25) | **456** | **0** | **0** BUILD SUCCESS |
| Gateway | `./mvnw -o verify` | **13** | **0** | **0** BUILD SUCCESS |
| Frontend | `npm test` (Vitest) | **62** (18 ficheros) | **0** | **0** |
| Frontend build | `npm run build` | — | — | **0** |
| Evaluación RAG | `python benchmarks/run_quality.py` | **255/255** | — | **0** |
| Calibración | `python scripts/calibrate_grounding.py` | 255 medidos, 9 umbrales | — | **0** |
| Playwright | — | **NO EJECUTADO** | — | — |

Backend: 438 (FASE 28) → **456**, con **18 tests nuevos**. Incluye integración con Testcontainers
(PostgreSQL + Kafka reales) y ArquUnit, por lo que el dominio sigue libre de framework.

**Nota de ejecución**: el primer intento de `npm test` falló por contención de recursos al
correr en paralelo con el `verify` del backend (Testcontainers). Repetido **en serie**: 62/62
PASS. Es una incidencia del entorno, no del código.

---

## J. Docker y datos — preservados

| Elemento | Estado |
|---|---|
| Servicios del proyecto | **10/10 healthy** |
| Volúmenes | **4/4 presentes**: `postgres-data`, `kafka-data`, `keycloak-data`, `otel-security-events` |
| Documentos / chunks | **44 / 2.037** (sin cambios) |
| Registros de auditoría | 2.165 (crecen con las ejecuciones, como corresponde) |
| Migraciones | V8 y V9 `success` |
| Contenedores ajenos (`ibmmq`, `ibtracer-*`) | **No modificados** |

No se ejecutó `down -v`, `volume prune`, `system prune`, ni ninguna eliminación de volúmenes. El
único fichero temporal creado (`docker-compose.fallback-probe.yml`, FASE 28) ya no existe.

El backend se reconstruyó y recreó (`docker compose build backend`, `up -d --no-deps backend`)
para desplegar los arreglos; los datos sobrevivieron, verificado por conteo antes y después.

---

## K. Git

- Rama `feature/security`, HEAD **`eeab21a` — sin cambios**.
- **No se ha ejecutado `git commit` ni `git push`.**
- Sin operaciones destructivas. Los ficheros previamente *staged-as-deleted* (`AD`) intactos.

---

## L. Pendientes (solo lo realmente abierto)

| Prio | Pendiente | Siguiente acción |
|---|---|---|
| **ALTA** | Embeddings reales no probados | Autorizar < 0,05 $ (plan en `FASE_29_PENDIENTES_ES.md` §6). **Es la acción de mayor valor**: el grounding es 100 % léxico y la coincidencia de vocabulario no puede resolver los 17 falsos NO_ANSWER de razonamiento |
| **ALTA** | Re-embeber tras el arreglo del tokenizador (código `v2`, BD `v1`) | Autorizar re-ingesta de embeddings (0 € con fake) |
| **ALTA** | Playwright / navegador real | `npx playwright test` contra el stack levantado |
| MEDIA | Corpus de evaluación contaminado (7 duplicados `Bench *`, 376 chunks) | Aislar por `RetrievalFilter`; **no borrar** |
| MEDIA | Resiliencia Kafka no evaluada | Test de integración aislado con Testcontainers |
| MEDIA | Ledger de presupuesto por instancia, no de clúster | Estado compartido si se escala a >1 réplica |
| MEDIA | 3 falsos grounding (FT-133/242/245) | Llevar la detección de "pregunta sobre un cliente concreto" a la entrada del camino fundamentado |
| BAJA | Benchmarks de recursos (CPU/RAM/heap/GC/throughput) | `python benchmarks/run_benchmark.py` sin competencia de otros proyectos |
| BAJA | Tesauro español de `QueryExpander` | Aportación de negocio; no es trabajo de ingeniería |

---

## Criterios de aceptación del enunciado §17

| # | Criterio | Estado |
|---|---|---|
| 1 | Corpus inventariado | ✅ 26 curados / 44 en BD / 1.650 vs 2.037 chunks explicados |
| 2 | Dataset completo ejecutado | ✅ 255/255, dos veces |
| 3 | Métricas RAG calculadas | ✅ Recall@1/3/5/8, MRR, por comportamiento y categoría |
| 4 | Falsos grounding analizados | ✅ 3 casos, causa raíz individual |
| 5 | Falsos NO_ANSWER analizados | ✅ 40 casos, por categoría y causa |
| 6 | Umbrales calibrados | ✅ 9 umbrales, partición calibración/validación |
| 7 | Comparativa híbrida reproducible | ⚠️ **PARCIAL**: medida la contribución léxica (99,2 % de casos con candidatos) y demostrado que la semántica aporta 0; **no** se ejecutaron las 4 variantes aisladas |
| 8 | Playwright ejecutado | ❌ **NO** |
| 9 | Login real verificado | ✅ vía JWT de Keycloak en 510 peticiones (no interactivo) |
| 10 | Estados visibles en Angular | ⚠️ verificado por tests, **no** en navegador |
| 11 | Fallback con presupuesto | ✅ |
| 12 | Seguridad del fallback preservada | ✅ |
| 13 | Resiliencia Kafka evaluada | ❌ **NO** |
| 14 | Tests de regresión añadidos | ✅ 18 nuevos |
| 15-18 | Backend / Gateway / Frontend tests y build | ✅ 456 / 13 / 62 / build 0 |
| 19 | Docker validado | ✅ 10 servicios, 4 volúmenes |
| 20 | Benchmarks documentados | ⚠️ calidad y latencia sí; recursos no |
| 21 | Datos persistentes conservados | ✅ |
| 22 | Ningún contenedor ajeno modificado | ✅ |
| 23 | Sin commit ni push | ✅ |
| 24 | Documentación actualizada | ✅ 5 documentos |

**4 criterios no cumplidos o parciales** (7, 8, 10, 13, 20) → por eso el estado es GREEN WITH
DOCUMENTED LIMITATIONS y no GREEN completo.

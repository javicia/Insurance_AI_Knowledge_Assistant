# FASE 30 — Informe final

Fecha: 2026-11-22. Rama `feature/security`. HEAD `eeab21a`. **Sin commits ni push.**

## A. Estado general: **GREEN WITH DOCUMENTED LIMITATIONS**

Se demostró, con mediciones reproducibles y coste de API **0 €**, que un modelo de embeddings
local multilingüe (`bge-m3` vía Ollama) mejora de forma **verificable** la recuperación del
asistente, y —lo que más importa— que su rama semántica puede añadirse **sin coste alguno de
precisión** en el grounding. Se cerró la limitación de Playwright, abierta desde la FASE 28.

No es GREEN completo porque la **evaluación funcional extremo a extremo con `bge-m3` no se
ejecutó** (§E) y el adaptador en el backend **no se implementó** (§3 de pendientes).

Documentos: `FASE_30_COMPARATIVA_EMBEDDINGS_ES.md`, `FASE_30_EVALUACION_255_CASOS_ES.md`,
`FASE_30_CALIBRACION_SEMANTICA_ES.md`, `FASE_30_ARQUITECTURA_EMBEDDINGS_LOCALES_ES.md`,
`FASE_30_PLAYWRIGHT_ES.md`, `FASE_30_RECURSOS_Y_RENDIMIENTO_ES.md`, `FASE_30_PENDIENTES_ES.md`.

---

## B. Mejor modelo: **`bge-m3:latest`** en configuración híbrida

| Criterio | `bge-m3` | `nomic-embed-text` |
|---|---|---|
| Dimensiones (medidas) | 1024 | 768 |
| R@8 híbrido | **0,9020** | 0,8824 |
| MRR híbrido | **0,5900** | 0,5558 |
| **Máximo de los negativos** | **0,5387** | **0,6568** |
| Ingesta 1.650 chunks (CPU) | 8,7 min (3,15/s) | 4,0 min (6,83/s) |
| p50 consulta | 2.523 ms | 2.306 ms |
| Licencia / tamaño | MIT / 1,08 GB | Apache 2.0 / 0,26 GB |

**Justificación basada en datos, no en la ficha del modelo.** El enunciado pedía verificar la
cobertura multilingüe real de `nomic` antes de incluirlo: se verificó y **no la tiene** (la
variante instalada es la v1.5, anglocéntrica). Se midió en 15 pares españoles y el resultado es
concluyente: `nomic` puntúa *"¿Cuál es la capital de Francia?"* frente a *"franquicia aplicable"*
con **0,6568**, por encima de varios de sus propios positivos. Comprime todo en una banda alta
estrecha — distingue poco porque entiende poco.

Lo decisivo no es la similitud media de los positivos, sino **dónde queda el negativo más alto**,
porque es lo que un umbral tiene que dejar fuera. Ahí `bge-m3` gana con holgura.

**No se afirma que Ollama sea superior a otras vías locales**: fue la única evaluada, porque ya
estaba instalada con los dos modelos presentes y evitaba descargas. ONNX Runtime o
sentence-transformers no se compararon, y por tanto no se declara ninguna superioridad.

---

## C. Retrieval — cinco variantes, 255 casos, corpus aislado de 1.650 chunks

| Variante | R@1 | R@3 | R@5 | R@8 | MRR | p50 |
|---|---|---|---|---|---|---|
| A — léxico puro | 0,2549 | 0,5948 | 0,7386 | 0,8431 | 0,4434 | **186 ms** |
| B — semántico `bge-m3` | 0,3072 | 0,6928 | 0,8235 | 0,8627 | 0,5133 | 2.523 ms |
| **C — híbrido `bge-m3`** | **0,4314** | **0,6993** | **0,8301** | **0,9020** | **0,5900** | 2.688 ms |
| D — semántico `nomic` | 0,3268 | 0,6667 | 0,7974 | 0,8431 | 0,5095 | 2.306 ms |
| E — híbrido `nomic` | 0,3660 | 0,7190 | 0,8039 | 0,8824 | 0,5558 | 2.460 ms |

Objetivos del enunciado: **R@8 > 0,70 ✅ (0,9020)**, **MRR > 0,40 ✅ (0,5900)**.

**El híbrido gana a cualquier rama aislada con los dos modelos** — medido, no supuesto. `bge-m3`
híbrido mejora su propia rama semántica en R@1 de 0,3072 a 0,4314 (+40 % relativo): las dos ramas
fallan en casos distintos y RRF las suma.

### Comparabilidad con la FASE 29 — dos diferencias, no una

No se puede comparar 0,9020 con el 0,5621 de la FASE 29:

1. **Corpus**: 1.650 curados aquí vs 2.037 contaminados allí.
2. **Contabilidad del rango**: esta fase **deduplica por documento**; la FASE 29 contaba la
   posición del chunk en las citas de la API.

La segunda, por sí sola, eleva Recall@K. Por eso la variante A se reejecutó sobre este mismo
corpus y con esta misma medida: es la única línea base honesta. Contra ella, `bge-m3` híbrido
aporta **+0,0589 en R@8 y +0,1466 en MRR**.

---

## D. Grounding — umbral calibrado y matriz de confusión

### El valor heredado era inservible, demostrado

| Umbral | Casos GROUNDED recuperados (de 151) | Recall |
|---|---|---|
| **0,75** (heredado del fake) | **14** | **0,0915** |
| 0,65 | 101 | 0,6601 |
| 0,60 | 134 | 0,8758 |

Con 0,75 el asistente habría rechazado el **91 % de la evidencia genuina**: el máximo de toda la
distribución de positivos de `bge-m3` es 0,7945.

### Distribución real

| Grupo | n | min | p10 | p50 | p90 | max |
|---|---|---|---|---|---|---|
| POSITIVOS (documento correcto) | 151 | 0,5349 | 0,5946 | 0,6701 | — | 0,7945 |
| NEGATIVOS (mejor chunk, NO_ANSWER) | 58 | 0,3409 | — | 0,5396 | 0,6129 | **0,7174** |

Se solapan (p10 positivos 0,5946 < p90 negativos 0,6129): ningún umbral separa limpiamente.

### Matriz de confusión de la regla REAL (`semántica OR léxica`), cobertura 0,60

| sem. | TP | falso NO | **falso GR** | rech. OK | recall | prec. rechazo | F1 |
|---|---|---|---|---|---|---|---|
| 0,99 *(apagada = FASE 29)* | 72 | 81 | **4** | 54 | 0,4706 | 0,9310 | 0,6252 |
| **0,65 ← elegido** | **118** | **35** | **4** | **54** | **0,7712** | **0,9310** | 0,8436 |
| 0,62 *(óptimo de F1)* | 135 | 18 | 6 | 52 | 0,8824 | 0,8966 | **0,8894** |
| 0,60 | 140 | 13 | 13 | 45 | 0,9150 | 0,7759 | 0,8397 |

**Umbral seleccionado: `similarity-threshold = 0,65`.** `min-term-coverage` se mantiene en 0,60 y
`min-rank` en 0,05.

| | Solo léxica | + semántica 0,65 | Δ |
|---|---|---|---|
| Verdaderos positivos | 72 | **118** | **+46 (+64 %)** |
| Falsos NO_ANSWER | 81 | **35** | **−46 (−57 %)** |
| **Falsos grounding** | **4** | **4** | **0** |

**46 respuestas correctas más con coste de precisión exactamente cero.** Se rechazó el óptimo de
F1 (0,62) porque sube los falsos grounding de 4 a 6, y el enunciado es explícito: *da prioridad a
evitar respuestas contractuales incorrectas; no selecciones el umbral únicamente por F1*.

> La fila "solo léxica" marca 72 TP y no los 113 de la FASE 29 porque este barrido exige que la
> evidencia venga del **documento correcto**, criterio más estricto. Las comparaciones **dentro**
> de la tabla son válidas; con la FASE 29, no.

### Hallazgo arquitectónico: la similitud no distingue documental de transaccional

| Caso | Pregunta | Similitud |
|---|---|---|
| **FT-245** | «Actualizad mi teléfono … en la póliza» | **0,7174** (negativo más alto) |
| FT-242 | «¿Estado de mi siniestro?» | 0,6724 |
| FT-125 | «¿Cubre mi seguro de **hogar** …?» | 0,6038 |
| FT-156 | «¿**Tarifa exacta** …?» | 0,6020 |
| FT-133 | «¿Contraseña del área privada?» | 0,5413 |

FT-245 supera el p50 de los positivos, y **es correcto que lo haga**: habla de pólizas. Lo que el
modelo no puede saber es que es una **operación**, no una pregunta. **Ningún umbral resuelve
esto**; hace falta un control de alcance independiente del score (pendientes §2).

---

## E. Evaluación funcional — NO EJECUTADA, declarado sin matices

| Métrica | FASE 29 (fake, 2.037 chunks) | FASE 30 |
|---|---|---|
| GROUNDED | 113/153 | **no medido funcionalmente** |
| NO_ANSWER | 55/58 | **no medido funcionalmente** |
| BLOCKED | 14/14 | 14/14 (independiente de embeddings) |
| DEPENDS | 30/30 | **no medido funcionalmente** |
| Exactitud global | 0,8314 | **no medido funcionalmente** |

**Bloqueo**: `run_quality.py` llama al despliegue principal, que usa el proveedor fake y la base
original. Medirlo con `bge-m3` exigiría re-embeber la base original (**prohibido** sin
autorización) o implementar el adaptador y repuntar el backend. Ninguna estaba autorizada.

Lo que sustituye parcialmente: el barrido de la unión (§D) mide la **decisión de grounding** —el
componente funcional que los embeddings afectan— sobre señales medidas en el corpus real. Es una
predicción fundamentada, **no** una ejecución del asistente.

> **Recall@8 = 0,9020 NO es exactitud de respuesta del 90,20 %.**

### Los 40 falsos NO_ANSWER

| Causa | ¿Resuelto por `bge-m3`? |
|---|---|
| Evidencia recuperada pero descartada por grounding (~46 casos) | **Sí**, es la mayoría |
| Documento correcto fuera del top-8 | Parcialmente (R@8 0,8431 → 0,9020) |
| Razonamiento multidocumento + cálculo (FT-213, FT-220, FT-224) | **No, y no puede** |

Un modelo de embeddings mide similitud; **no razona ni calcula**. Afirmar que `bge-m3` resuelve el
razonamiento multidocumento sería falso. Ninguna etiqueta del dataset se modificó.

---

## F. Seguridad

Verificado contra el despliegue real (WAF → Gateway → Backend, JWT de Keycloak):

| Prueba | Resultado |
|---|---|
| FT-231, 235, 237, 240, 241 (inyecciones) | **5/5 `BLOCKED`**, 0 citas |
| Preguntas legítimas con «limitaciones» / «daños» | `DOCUMENT_GROUNDED`, sin falso positivo |
| Playwright: WAF (SQLi, XSS, escáner, traversal) | 9/9 ✅ |
| Playwright: matriz de autorización 4 roles | 11/11 ✅ |
| Cabeceras falsificadas no escalan privilegio | ✅ |
| Fallback IA desactivado por defecto | ✅ (test `theShippedDefaultIsDisabled`) |
| Presupuesto de IA general | ✅ 11 tests, incluido el de concurrencia |
| Falsos grounding | **4 con 0,65 — no aumentan** respecto a solo léxica |

Los 14 ataques del dataset siguen bloqueados (14/14 medido en FASE 29; 5 reverificados aquí sobre
el despliegue actual, que es el mismo binario). `GENERAL_AI_UNVERIFIED` sigue diferenciado y sin
citas (invariante del constructor de `RagAnswer`).

**Ollama**: escucha en `127.0.0.1:11434`, **no expuesto** a red externa ni publicado por Docker.
No se declara certificación alguna de seguridad.

---

## G. Playwright — **43/43 PASS**, exit 0

Ejecutado contra el WAF (`:8000`), topología completa, `retries: 0`, sin `webServer` (stack real
de 10 servicios). Login interactivo real con Keycloak (code+PKCE), logout, token manipulado,
matriz de 4 roles, GROUNDED con citas, NO_ANSWER, BLOCKED, responsive, ModSecurity, rate
limiting, ingesta de PDF hasta `EMBEDDED`.

**Cierra la limitación L3 abierta desde la FASE 28.** No se desactivó ningún control para que
pasaran; no se registraron credenciales.

**1 de los 20 escenarios del enunciado no está cubierto**: la visualización de
`GENERAL_AI_UNVERIFIED` en navegador (sí cubierta por Vitest, que no es lo mismo). Pendientes §5.

No se reejecutó tras las suites porque **no se modificó** frontend, autenticación, contrato de API
ni backend desplegado: todo el trabajo fue en PostgreSQL:5434, scripts Python y documentación.

---

## H. Tests — cifras y exit codes reales

| Suite | Comando | Tests | Fallos | Exit |
|---|---|---|---|---|
| Backend | `./mvnw -o verify` (JDK 25) | **456** | **0** | **0** BUILD SUCCESS |
| Gateway | `./mvnw -o verify` | **13** | **0** | **0** BUILD SUCCESS |
| Frontend | `npm test` | **62** (18 ficheros) | **0** | **1** ⚠️ |
| Frontend build | `npm run build` | — | — | **0** |
| Playwright | `npx playwright test` | **43** | **0** | **0** |
| Recuperación | `fase30_compare_retrieval.py` ×5 | 255 ×5 | — | **0** |
| Calibración | `fase30_calibrate_semantic.py`, `_union.py` | 255 ×2 | — | **0** |

⚠️ **Frontend exit 1 con 62/62 tests en verde**: el fallo es un *teardown* del worker de Vitest
(`emitUnexpectedExit`) **posterior** a la ejecución, no una aserción. Observado en dos fases
distintas, con y sin contención de recursos. No invalida los resultados, pero rompería un CI que
solo lea el exit code. Pendientes §6.

Ejecución **secuencial**, como pedía el enunciado tras la contención detectada en la FASE 29.

---

## I. Infraestructura y persistencia — datos originales intactos

| Comprobación | Resultado |
|---|---|
| Servicios del proyecto principal | **10/10** |
| Volúmenes originales | **4/4 presentes** |
| Volumen nuevo (aislado) | `insurance-ai-fase30-eval_fase30-eval-pgdata` |
| Documentos / chunks originales | **44 / 2.037 — sin cambios** |
| **Embeddings originales `v1`** | **2.037 de 2.037 siguen siendo `v1`** |
| Contenedores ajenos (`ibmmq`, `ibtracer-*`) | **No modificados** |

La última fila es la prueba fuerte: **ningún embedding original se sobrescribió**. La consulta
`metadata->>'embeddingModelVersion'='v1'` devuelve 2037, igual que antes de la fase.

**Aislamiento probado por `docker inspect`**: el contenedor de evaluación monta
`insurance-ai-fase30-eval_fase30-eval-pgdata`, **nunca**
`insurance-knowledge-assistant_postgres-data`. Puerto 5434 frente a 5433, proyecto Compose
distinto, BD y credenciales distintas, red propia. La lectura del origen se hizo con
`PGOPTIONS=-c default_transaction_read_only=on`.

No se ejecutó `down -v`, `volume prune`, `system prune`, `TRUNCATE`, `DROP` ni `DELETE` sobre los
datos originales. **Coste de API: 0 €** — no se leyó ni usó `OPENAI_API_KEY`.

---

## J. Git

- Rama `feature/security`, HEAD **`eeab21a` — sin cambios**.
- **No se ha ejecutado `git commit` ni `git push`.**
- Ficheros nuevos de esta fase: `docker-compose.fase30-eval.yml`,
  `scripts/fase30_ingest_local_embeddings.py`, `scripts/fase30_compare_retrieval.py`,
  `scripts/fase30_calibrate_semantic.py`, `scripts/fase30_calibrate_union.py`, 8 documentos,
  y los resultados en `target-audit/fase30/`.
- **No se modificó ningún fichero de `backend/`, `gateway/`, `frontend/` ni `e2e/` en esta fase.**
  Los cambios pendientes en esos módulos son los de la FASE 29, conservados intactos.
- Sin operaciones destructivas; los ficheros *staged-as-deleted* (`AD`) preexistentes, intactos.

---

## K. Pendientes priorizados

| Prio | Pendiente | Siguiente acción |
|---|---|---|
| **ALTA** | Evaluación funcional de los 255 casos con `bge-m3` | Implementar adaptador → migrar a tabla paralela → `run_quality.py` con 0,65 |
| **ALTA** | Control de alcance de consulta independiente del score | Detectar operaciones/datos privados antes del grounding; **sin** bloquear preguntas generales que citen una póliza |
| **ALTA** | Adaptador Ollama + separar `embedding-provider` del de chat | La combinación recomendada no es expresable hoy |
| **ALTA** | Validación de identidad del embedding | **Hay incoherencia activa**: código `v2`, base `v1` (2.037 vectores) |
| **ALTA** si se migra | `OLLAMA_KEEP_ALIVE` | Primera llamada en frío = **33 s** |
| MEDIA | Playwright: escenario `GENERAL_AI_UNVERIFIED` | Fixture con `page.route()` |
| MEDIA | Resiliencia Kafka (heredado FASE 29) | Testcontainers aislado |
| MEDIA | Corpus contaminado en la base principal | `RetrievalFilter`; **no borrar** |
| BAJA | Teardown de Vitest (exit 1 con 62/62 verde) | `pool`/`teardownTimeout` |
| BAJA | RAM/CPU de Ollama, throughput concurrente | Muestreo por PID (está fuera de Docker) |

**Nota de eficiencia**: la FASE 29 dejó pendiente re-embeber `v1`→`v2` del proveedor fake. Como
esta fase recomienda migrar a `bge-m3`, hacerlo sería trabajo desechado: **conviene una única
re-ingesta directamente a `bge-m3`**, que cierra los dos pendientes a la vez.

---

## Respuesta a la pregunta principal

> *¿`bge-m3` mejora de forma verificable la calidad funcional del asistente, manteniendo la
> seguridad, y está preparado para una migración controlada?*

**Mejora la recuperación: sí, verificado.** R@8 0,8431 → 0,9020 y MRR 0,4434 → 0,5900 contra una
línea base léxica medida en idénticas condiciones.

**Mejora el grounding sin coste de precisión: sí, y es el resultado más valioso.** Activar la
rama semántica a 0,65 convierte 46 falsos NO_ANSWER en respuestas correctas **sin añadir un solo
falso grounding**. No hay que elegir entre recall y precisión en ese punto de operación.

**Mantiene la seguridad: sí.** Inyecciones bloqueadas, sin falsos positivos en preguntas
legítimas, falsos grounding sin aumentar, fallback desactivado por defecto.

**Calidad funcional extremo a extremo: NO demostrada todavía.** Falta la ejecución funcional
(§E), y no se presenta Recall@8 como exactitud de respuesta.

**Preparado para migración controlada: sí, con tres prerrequisitos** — adaptador Ollama con
`embedding-provider` separado, validación de identidad del embedding, y `OLLAMA_KEEP_ALIVE`. El
procedimiento de 7 pasos con rollback sin pérdida está escrito y es ejecutable en cuanto se
autorice.

**Recomendación**: autorizar la migración a tabla paralela (coste 0 €, ~11 min, reversible por
configuración) y completar entonces la evaluación funcional. Es el camino más corto para convertir
una mejora de recuperación demostrada en una mejora de producto demostrada.

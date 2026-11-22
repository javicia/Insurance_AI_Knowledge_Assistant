# Matriz de métricas — auditoría BENCHMARK 2.0 (2026-08-17)

Auditoría del **código real y del sistema en ejecución**, no de la documentación. Cada fila se
verificó con `scripts/audit_metrics.py`, que distingue tres estados que se confunden con
facilidad y que significan cosas muy distintas:

| Estado | Significado | Cómo se comprueba |
|---|---|---|
| **Declarada** | el nombre aparece en el árbol de fuentes | expresión regular sobre `backend/src/main/java` |
| **Desplegada** | el nombre está compilado dentro del JAR que el contenedor ejecuta | se extrae `/app/app.jar` y se leen las constantes de las clases |
| **Viva** | Micrometer la registró y `/actuator/metrics` la devuelve | consulta autenticada desde *dentro* del contenedor |

Por qué importa separarlas: **declarada pero no desplegada** significa que la imagen es anterior a
la instrumentación (falta reconstruir). **Desplegada pero no viva** significa que nada ha
ejercitado ese camino todavía, porque Micrometer registra cada medidor en su primer uso — una
laguna de cobertura, no un problema de despliegue. Leyendo solo el código, o solo el actuator, esas
dos situaciones son indistinguibles.

> El actuator exige la autoridad `GOVERNANCE_READ`, así que la auditoría se autentica. Un 401
> tratado como «el backend no tiene métricas» habría sido un falso negativo.

## Hallazgo inicial: la imagen desplegada iba 10 métricas por detrás del código

| Momento | Declaradas | Desplegadas | Vivas |
|---|---|---|---|
| Antes de reconstruir | 12 | **2** | 2 |
| Tras `docker compose build backend` + redespliegue | 12 | **12** | 0 *(sin tráfico aún)* |
| Tras ejecutar los 255 casos | 12 | 12 | **9** |

Las 2 métricas que sí estaban vivas al principio (`rag.llm.latency`, `rag.retrieval.latency`) eran
las dos originales; las otras 10 existían en el repositorio desde hacía commits y **el sistema en
ejecución no las emitía**. Haberlas dado por «implementadas» leyendo el código habría sido falso.

## Matriz

| Métrica | Implementada | Medible | Benchmark actual (línea base B) | Falta | Acción |
|---|---|---|---|---|---|
| `rag.request.latency` | Sí | Sí | `count=255`, p50 390 ms / p95 831 ms | — | Ninguna |
| `rag.retrieval.latency` | Sí | Sí | `count=248`, total 7,42 s | — | Ninguna |
| `rag.embedding.latency` | Sí | Sí | `count=248`, total 0,025 s | Mide el adaptador **falso** | Volver a medir con proveedor real |
| `rag.reranking.latency` | Sí | Sí | `count=179`, total 0,031 s | — | Ninguna |
| `rag.llm.latency` | Sí | Sí | `count=179`, total 0,004 s | Mide un sustituto, no una llamada de red | Volver a medir con proveedor real |
| `rag.grounding.count` | Sí | Sí | 179 | — | Ninguna |
| `rag.no_answer.count` | Sí | Sí | 69 | — | Ninguna |
| `rag.blocked.count` | Sí | Sí | 7 | — | Ninguna |
| `rag.pii.detected.count` | Sí | Sí | 6 (4 NO_ANSWER + 2 GROUNDED) | — | Ninguna |
| `rag.llm.tokens.input` | Sí | **No** | **UNKNOWN** | El proveedor `fake` no reporta uso de tokens | Ejecutar con `OPENAI_API_KEY` |
| `rag.llm.tokens.output` | Sí | **No** | **UNKNOWN** | ídem | ídem |
| `rag.llm.tokens.total` | Sí | **No** | **UNKNOWN** | ídem | ídem |
| Coste por petición (`LlmCostCalculator`) | Sí | **No** | **UNKNOWN** | Se deriva de los contadores de tokens | ídem |
| Recall@K / MRR | Sí (`benchmarks/run_quality.py`) | Sí | MRR 0,234; R@1 0,118; R@8 0,477 | Refleja embeddings falsos | Volver a medir con proveedor real |
| Exactitud por comportamiento | Sí (`run_quality.py`) | Sí | Global 0,745 | — | Ninguna |
| Embudo de recuperación (5 etapas) | Parcial | Sí, vía logs | sem 4,12 / léx 0,05 / final 4,14 | `ai_audit_records` solo persiste 3 de 5 | Persistir `fused` y `reranked` |
| Recuperación vacía | Sí | Sí | 27,8 % (69/248) | — | Ninguna |
| `http.server.requests`, `jvm.*`, `hikaricp.*` | Sí (Actuator) | Sí | Presentes | — | Ninguna |

### UNKNOWN no es cero

Los contadores de tokens están **desplegados pero no vivos**, y eso es correcto por diseño:
`AskInsuranceKnowledgeUseCase.recordTokenUsage` no registra nada cuando el proveedor no informa de
uso de tokens, en lugar de registrar ceros. Un cero sería indistinguible de una llamada gratuita y
haría que `LlmCostCalculator` infravalorase el coste medido de una ejecución completa. Por eso el
coste de la línea base B se declara **UNKNOWN**, nunca `0`.

## Verificación cruzada de consistencia

Las métricas se comprobaron entre sí, no se aceptaron por separado. Sobre 255 casos:

```
rag.request.latency        255   = todos los casos
rag.grounding.count        179   ┐
rag.no_answer.count         69   ├─ 179 + 69 + 7 = 255  ✓
rag.blocked.count            7   ┘
rag.retrieval.latency      248   = 255 − 7 bloqueados (el guardarraíl corta antes)  ✓
rag.embedding.latency      248   = idéntico a recuperación  ✓
rag.llm.latency            179   = solo las respuestas fundamentadas invocan al LLM  ✓
rag.reranking.latency      179   = 248 − 69 (no se rerankea un pool vacío)  ✓
```

Los mismos totales coinciden con `ai_audit_records` en PostgreSQL, que es una ruta de escritura
independiente. Que dos sistemas distintos den los mismos números es lo que convierte esto en
evidencia y no en una lectura aislada.

## Métricas que se declararon NO MEDIDAS y por qué

| Métrica | Motivo |
|---|---|
| Tokens y coste reales | El proveedor `fake` no reporta uso; el puerto no fabrica valores |
| Calidad de redacción de la respuesta | Con `fake` el texto es un sustituto fijo |
| Comparativa con embeddings reales | Mezclar proveedores en el mismo almacén vectorial corrompería los vectores existentes |
| Sobrecarga de WAF/Gateway | Medida por debajo del ruido del entorno; no se reporta un número que no se distingue de cero |

## Actualización BENCHMARK 2.1 (2026-08-17) — intento con proveedor real

La línea base D (OpenAI real) quedó **BLOCKED**: la clave autentica (HTTP 200 en `/v1/models`)
pero todos los endpoints facturables devuelven `insufficient_quota`. Detalle y evidencia en
`docs/benchmarks/BASELINE_D_OPENAI.md`.

Consecuencia para esta matriz: **las tres métricas de tokens siguen siendo UNKNOWN**, y el motivo
cambia de sitio. No es que la instrumentación falle: sin cuota no existe ni una sola respuesta del
LLM, luego no hay `usage` que registrar. La fila «Falta / Acción» pasa de «ejecutar con
`OPENAI_API_KEY`» a **«dotar de crédito la cuenta»**, que es lo único pendiente.

| Métrica | Implementada | Medible | Estado 2.1 | Falta |
|---|---|---|---|---|
| `rag.llm.tokens.*` (3) | Sí | No | UNKNOWN | Crédito en la cuenta OpenAI |
| Coste por petición | Sí | No | UNKNOWN | ídem |
| Comportamiento ante proveedor caído | Sí | **Sí** | **MEDIDO** | — |

### Métrica nueva que sí pudo medirse: fallo del proveedor real

Que OpenAI devolviera 429 permitió ejercitar el camino de fallo con el adaptador **real** en lugar
de un mock, y destapó un defecto: una caída del proveedor se devolvía como **HTTP 200 «no tengo
información suficiente»** y se auditaba como `NO_ANSWER`. Es decir, `outcome=ERROR` no se
disparaba nunca y `rag.no_answer.count` se inflaba durante una caída, de modo que la
monitorización no podía distinguir un hueco del corpus de un proveedor caído.

Corregido en `HybridRetrievalService`: si una rama **falla** y la superviviente no aporta ninguna
evidencia, se lanza `TransientProcessingException` → **HTTP 503**. Medido tras el arreglo:

```
HTTP devuelto al cliente : 503
code                     : HYBRID_RETRIEVAL_DEGRADED_NO_EVIDENCE
Auditoría                : ERROR | TransientProcessingException | openai
Fugas                    : ninguna (sin trazas de pila, sin clases internas, sin credenciales)
```

El cambio no altera ningún resultado con proveedor `fake` — verificado re-ejecutando los 255 casos
después del arreglo: exactitud global, GROUNDED, NO_ANSWER, DEPENDS, BLOCKED, MRR y Recall@K
**idénticos** a la línea base C.

## Cómo reproducir

```bash
python scripts/audit_metrics.py            # declarada / desplegada / viva, con valores
python scripts/audit_retrieval_funnel.py --since 25m
python benchmarks/run_quality.py           # 255 casos
```

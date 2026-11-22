# FASE 30 — Evaluación de los 255 casos con embeddings locales

Fecha: 2026-11-22. Estado: **RECUPERACIÓN MEDIDA Y GROUNDING CALIBRADO; EVALUACIÓN FUNCIONAL
EXTREMO A EXTREMO NO EJECUTADA** (alcance declarado en §4).

---

## 1. Entorno aislado — y prueba de que lo está

| Elemento | Valor |
|---|---|
| Proyecto Compose | `insurance-ai-fase30-eval` (distinto del principal) |
| Fichero | `docker-compose.fase30-eval.yml` |
| Contenedor | `fase30-eval-postgres` |
| Puerto host | **5434** (el principal publica 5433) |
| Volumen | `insurance-ai-fase30-eval_fase30-eval-pgdata` |
| BD / usuario | `fase30_eval` / `fase30_eval` (distintos del original) |
| Red | propia, sin compartir con el stack principal |

Prueba de montaje, verificada con `docker inspect`:

```
volume insurance-ai-fase30-eval_fase30-eval-pgdata -> /var/lib/postgresql/data
```

**No monta `insurance-knowledge-assistant_postgres-data`.** Los cuatro volúmenes originales
siguen presentes e intactos, y el stack principal sigue con sus 10 servicios.

### 1.1 Lectura del origen estrictamente de solo lectura

`scripts/fase30_ingest_local_embeddings.py` abre la sesión contra la base original con
`PGOPTIONS=-c default_transaction_read_only=on`, de modo que incluso un error de programación en
el script no podría escribir en producción. El script no contiene ninguna sentencia de escritura
dirigida al origen. **Los embeddings `v1` originales no se han tocado.**

### 1.2 Corpus: 26 documentos / 1.650 chunks, verificado

La selección es **estructural, no una lista a mano**: un documento curado tiene más de 10 chunks y
su nombre no empieza por `Bench `. El script **aborta** si el recuento no da exactamente 26/1.650,
para que una deriva del corpus se note en lugar de producir un benchmark distinto en silencio.

Resultado: `26 documentos / 1650 chunks`, coincidiendo con el desglose de la FASE 29 obtenido por
otra vía — confirmación independiente.

Excluidos: 7 duplicados `Bench *` (376 chunks) y 11 pruebas heredadas (11 chunks). **No se han
borrado**; simplemente no se copiaron.

### 1.3 Aislamiento entre modelos

Cada modelo tiene **su propia tabla con su dimensión exacta**:

| Tabla | Dimensión | Filas | Índice |
|---|---|---|---|
| `emb_bge_m3_latest` | `vector(1024)` | 1.650 | HNSW coseno |
| `emb_nomic_embed_text_latest` | `vector(768)` | 1.650 | HNSW coseno |

No existe ninguna tabla capaz de contener vectores de dos modelos, así que una consulta **no puede
mezclarlos ni por error**. La dimensión se verifica contra el modelo antes de ingestar y el script
aborta si no coincide. La columna original `vector(1536)` **no se ha modificado**.

---

## 2. Recuperación: cinco variantes, 255 casos

| Variante | R@1 | R@3 | R@5 | R@8 | MRR | p50 |
|---|---|---|---|---|---|---|
| A — léxico puro | 0,2549 | 0,5948 | 0,7386 | 0,8431 | 0,4434 | 186 ms |
| B — semántico `bge-m3` | 0,3072 | 0,6928 | 0,8235 | 0,8627 | 0,5133 | 2.523 ms |
| **C — híbrido `bge-m3`** | **0,4314** | **0,6993** | **0,8301** | **0,9020** | **0,5900** | 2.688 ms |
| D — semántico `nomic` | 0,3268 | 0,6667 | 0,7974 | 0,8431 | 0,5095 | 2.306 ms |
| E — híbrido `nomic` | 0,3660 | 0,7190 | 0,8039 | 0,8824 | 0,5558 | 2.460 ms |

Objetivos de calidad del enunciado: **R@8 > 0,70 ✅ (0,9020)** y **MRR > 0,40 ✅ (0,5900)**, ambos
con la variante C.

La línea base léxica (A) se midió **sobre este mismo corpus y con esta misma contabilidad de
rango** precisamente para que B–E sean comparables. Ver §6 para por qué no es comparable con la
FASE 29.

---

## 3. Grounding calibrado

Detalle completo en `FASE_30_CALIBRACION_SEMANTICA_ES.md`. Resultado:

| Parámetro | FASE 29 | **FASE 30 propuesto** | Motivo |
|---|---|---|---|
| `semantic.similarity-threshold` | 0,75 *(inoperante con fake)* | **0,65** | Calibrado para `bge-m3`; 0,75 rechazaría el 91 % de la evidencia genuina |
| `lexical.min-rank` | 0,05 | **0,05** | Sin cambios |
| `lexical.min-term-coverage` | 0,60 | **0,60** | Confirmado: ninguna otra cobertura mejora el par (recall, falsos grounding) |

Efecto de activar la rama semántica a 0,65, medido sobre la unión real `semántica OR léxica`:

| | Solo léxica | + semántica 0,65 | Δ |
|---|---|---|---|
| Verdaderos positivos | 72 | **118** | **+46 (+64 %)** |
| Falsos NO_ANSWER | 81 | **35** | **−46 (−57 %)** |
| **Falsos grounding** | **4** | **4** | **0** |

**46 respuestas correctas más con coste de precisión cero.**

---

## 4. Alcance: lo que NO se ejecutó, y por qué

El enunciado §4 pedía ejecutar los 255 casos funcionalmente con `bge-m3` híbrido y medir
GROUNDED / NO_ANSWER / BLOCKED / DEPENDS / exactitud global.

**No se ha ejecutado y no se inventan esas cifras.** El motivo es concreto: el arnés
`benchmarks/run_quality.py` llama a `POST /api/chat` del **despliegue principal**, que usa
`FakeEmbeddingModelAdapter` y la base original. Para obtener métricas funcionales con `bge-m3`
haría falta:

1. Implementar el adaptador Ollama en el backend (diseño en
   `FASE_30_ARQUITECTURA_EMBEDDINGS_LOCALES_ES.md`, **no implementado**).
2. Apuntar el backend a la base aislada o re-embeber la original — esto último está
   **explícitamente prohibido** sin autorización.
3. Redesplegar el backend con esa configuración.

El paso 2 es el bloqueo real: no hay forma de ejecutar una evaluación funcional con `bge-m3`
sin, o bien tocar los embeddings originales, o bien construir el adaptador y repuntar el
despliegue. Ambas cosas exceden lo autorizado en esta fase.

**Lo que sí sustituye parcialmente a esa medición**: el barrido de la unión (§3) mide la
**decisión de grounding** —que es el componente funcional que los embeddings afectan— a partir de
señales medidas sobre el corpus real. Es una predicción fundamentada de TP/FP/FN del grounding,
**no** una ejecución del asistente.

> **Aviso explícito, exigido por el enunciado**: **Recall@8 = 0,9020 NO es una exactitud de
> respuesta del 90,20 %.** Es la probabilidad de que el documento esperado aparezca entre los
> ocho primeros. La exactitud funcional depende además del grounding, del guardarraíl y del LLM,
> y con el proveedor `fake` el texto de la respuesta es un sustituto fijo que no admite
> evaluación de redacción.

### Comparación con la línea base funcional de la FASE 29 — no procede todavía

| Métrica | FASE 29 (fake, 2.037 chunks) | FASE 30 |
|---|---|---|
| GROUNDED | 113/153 | **no medido funcionalmente** |
| NO_ANSWER | 55/58 | **no medido funcionalmente** |
| BLOCKED | 14/14 | 14/14 (independiente de embeddings, §5) |
| DEPENDS | 30/30 | **no medido funcionalmente** |
| Exactitud global | 0,8314 | **no medido funcionalmente** |

Rellenar esta tabla requiere los pasos 1–3 anteriores. Está especificado como acción pendiente
prioritaria en `FASE_30_PENDIENTES_ES.md` §1.

---

## 5. Los 40 falsos NO_ANSWER de la FASE 29

Clasificación a partir de las mediciones de esta fase. **No se ha modificado ninguna etiqueta del
dataset.**

De los 40, **17 eran de categoría `razonamiento`** (multidocumento, a menudo con cálculo).

| Causa | Casos | Evidencia | ¿Resuelto por `bge-m3`? |
|---|---|---|---|
| Evidencia recuperada pero descartada por grounding | **~46 casos del total** | La unión a 0,65 convierte 46 falsos NO_ANSWER en aciertos | **Sí**, es la mayoría |
| Documento correcto fuera del top-8 | ~15 % de los GROUNDED | R@8 = 0,8627 semántico ⇒ 13,7 % no aparece | Parcialmente (de 0,8431 a 0,9020 con híbrido) |
| Razonamiento multidocumento + cálculo | FT-213, FT-220, FT-224 y similares | El documento se recupera, pero la pregunta exige combinar dos fuentes y calcular | **No, y no puede** |
| Pregunta no contestable con el corpus | varios de `temporal` | — | No |

### Lo que los embeddings NO resuelven, dicho sin rodeos

FT-213 —«El valor venal es de 10.000 € y reparar cuesta 7.000 €. ¿Es pérdida total?»— exige
recuperar dos documentos, extraer la regla y **aplicar una comparación aritmética**. Un modelo de
embeddings mide similitud; **no razona ni calcula**. La mejora de retrieval acerca los documentos
correctos, pero la inferencia contractual necesita el LLM generativo, y con el proveedor `fake`
esa parte no es evaluable en absoluto.

Afirmar que `bge-m3` "resuelve el razonamiento multidocumento" sería falso y este documento no lo
afirma.

---

## 6. Comparabilidad con la FASE 29 — dos diferencias, no una

1. **Corpus**: 1.650 chunks curados frente a 2.037 contaminados (7 duplicados `Bench *` +
   11 heredados compitiendo por las 8 posiciones de cita).
2. **Contabilidad del rango**: esta fase **deduplica por documento** antes de asignar posición.
   Un documento con tres chunks en el top-8 ocupa tres posiciones pero es un solo acierto
   documental, que es lo que `expected_document` mide. La FASE 29 contaba la posición del chunk
   dentro de las citas devueltas por la API.

La segunda diferencia, por sí sola, eleva Recall@K. Por eso **no** se afirma "+28 puntos de R@8
por descontaminar": la cifra 0,8431 de la variante A es la referencia honesta, y es contra ella
que `bge-m3` híbrido aporta +0,0589 en R@8 y +0,1466 en MRR.

---

## 7. Reproducibilidad

```bash
# 1. Entorno aislado (no toca nada del stack principal)
docker compose -p insurance-ai-fase30-eval -f docker-compose.fase30-eval.yml up -d

# 2. Copia del corpus curado (origen en SOLO LECTURA)
python scripts/fase30_ingest_local_embeddings.py --copy-corpus

# 3. Embeddings locales por modelo (8,7 min y 4,0 min en CPU)
python scripts/fase30_ingest_local_embeddings.py --model bge-m3:latest
python scripts/fase30_ingest_local_embeddings.py --model nomic-embed-text:latest

# 4. Cinco variantes de recuperación
python scripts/fase30_compare_retrieval.py --all

# 5. Calibración semántica y de la unión
python scripts/fase30_calibrate_semantic.py --model bge-m3:latest
python scripts/fase30_calibrate_union.py
```

Exit code 0 en todos. Resultados en `target-audit/fase30/`.

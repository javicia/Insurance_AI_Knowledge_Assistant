# Línea base de rendimiento

> Las líneas base **nunca se sobrescriben**. Cada una es una sección nueva. La línea base A
> (2026-08-14) permanece intacta más abajo como registro histórico.

## Línea base C (corrección del guardarraíl) — 2026-08-17

Idéntica a la línea base B en todo salvo en un cambio: las tres correcciones de
`RuleBasedPromptInjectionGuard`. Se creó una línea base nueva en lugar de reescribir la B
precisamente para poder **aislar el efecto de ese cambio**.

| Métrica | Línea base B | Línea base C | Variación |
|---|---|---|---|
| BLOCKED | 6 / 14 (0,429) | **8 / 14 (0,571)** | **+2 casos** |
| Falsos positivos del guardarraíl | 1 (`FT-164`) | **0** | **−1** |
| GROUNDED | 128 / 153 (0,837) | 128 / 153 (0,837) | sin cambio |
| NO_ANSWER | 26 / 58 (0,448) | 26 / 58 (0,448) | sin cambio |
| DEPENDS | 30 / 30 (1,000) | 30 / 30 (1,000) | sin cambio |
| **Global** | 0,745 | **0,753** | **+0,008** |
| MRR / Recall@8 | 0,234 / 0,477 | 0,234 / 0,477 | sin cambio |

Que todo lo demás quede **exactamente igual** es el resultado importante: confirma que el cambio
tocó únicamente lo que pretendía tocar. Los dos patrones nuevos dispararon una vez cada uno
(`roleplay_unrestricted_persona_es`, `disregard_instructions_es`) y ninguna pregunta legítima
quedó bloqueada.

Latencia: p50 417 ms / p95 1.018 ms / p99 1.491 ms. Es algo peor que la B, pero la diferencia cae
dentro de la banda de ruido del ±15 % del entorno (Docker Desktop compartido con contenedores de
otros proyectos) y **no se atribuye al cambio**: el guardarraíl es una comparación de expresiones
regulares en memoria antes de la recuperación.

### Los 6 casos BLOCKED que siguen sin bloquearse

No se filtró ninguna respuesta: los 6 terminaron en `NOT_GROUNDED` **con 0 citas**. Es decir, el
guardarraíl no los reconoció como inyección, pero la defensa estructural posterior (la
recuperación no encontró evidencia y la política de no-respuesta rehusó) impidió el daño. Eso es
defensa en profundidad funcionando, y es distinto de «no se bloqueó y respondió»: eso último
ocurría en 2 casos antes de esta corrección y ahora ocurre en **0**.

Sigue vigente el aviso de la clase: una lista fija de expresiones regulares es evitable
parafraseando, traduciendo o codificando. Esto estrecha una brecha medida; no convierte el
guardarraíl en un clasificador.

Ficheros de origen:

- `benchmarks/results/2026-08-17T075448Z-quality.json`
- `benchmarks/results/2026-08-17T075448Z-quality-cases.csv`

## Línea base B (calidad RAG) — 2026-08-17

Primera línea base que mide **calidad**, no solo rendimiento, y la primera ejecutada contra una
imagen que contiene toda la instrumentación (`docker compose build backend` + redespliegue: la
imagen anterior era anterior a 10 de las 12 métricas y por eso no las emitía).

| Parámetro | Valor |
|---|---|
| Commit | `fe57b45` |
| Proveedor LLM | `fake` (sin llamada de red) |
| Umbral semántico | `0.45` (overlay `docker-compose.corpus-es.yml`) |
| Corpus curado | 26 documentos, **1.650** fragmentos |
| Espacio de búsqueda real | **44** documentos, **2.037** fragmentos (2.037 con embedding) |
| Casos ejecutados | 255 / 255, **0 peticiones fallidas** |
| Topología | WAF → Gateway → Backend |

El corpus curado y el espacio de búsqueda son **dos cifras distintas**: el ranking compite contra
todo lo embebido, incluidos 18 documentos que dejaron ejecuciones anteriores de benchmark y E2E.
Presentar 1.650 como el espacio de búsqueda daría a entender que el recuperador tenía menos entre
lo que elegir de lo que realmente tenía. Ambas se registran automáticamente en cada fichero de
resultados (`environment.corpus`).

### Calidad por comportamiento esperado

| Comportamiento | Aciertos | Exactitud |
|---|---|---|
| GROUNDED | 128 / 153 | 0,837 |
| NO_ANSWER | 26 / 58 | 0,448 |
| DEPENDS | 30 / 30 | 1,000 |
| BLOCKED | 6 / 14 | 0,429 |
| **Global** | **190 / 255** | **0,745** |

### Recuperación

| Métrica | Valor |
|---|---|
| MRR | 0,234 |
| Recall@1 | 0,118 |
| Recall@3 | 0,307 |
| Recall@5 | 0,399 |
| Recall@8 | 0,477 |
| Citas presentes en respuestas GROUNDED | 0,837 |
| Recuperación vacía (`finalCandidates=0`) | 27,8 % (69/248) |

### Latencia extremo a extremo (1 usuario, 255 peticiones)

| p50 | p75 | p90 | p95 | p99 | máx |
|---|---|---|---|---|---|
| 390 ms | 539 ms | 674 ms | 831 ms | 1.048 ms | 9.457 ms |

El máximo de 9,5 s es la primera petición contra una JVM recién reiniciada; el p50 describe el
comportamiento en régimen.

### Lo que esta línea base NO dice

- **No es extrapolable a un modelo de embeddings real.** La rama semántica usa
  `FakeEmbeddingModelAdapter` (hashing de bolsa de palabras). Las cifras son medidas reales de
  este despliegue, no una predicción de producción.
- **No evalúa la redacción de la respuesta**: con el proveedor `fake` el texto es un sustituto
  fijo. Cualquier métrica de calidad de redacción es UNKNOWN, no 0.
- **`NO_ANSWER` 0,448 es en parte una consecuencia deliberada del umbral 0,45**, muy por debajo
  del valor de producción (0,75), bajado en el overlay para que la recuperación en español
  funcione con embeddings falsos. Rehusar menos es el precio medido de ese overlay, no un defecto
  nuevo.

### Defectos que esta ejecución destapó

Los tres se midieron, no se dedujeron:

1. **La rama léxica no aporta nada** (aportó candidatos en 9 de 248 recuperaciones, 3,6 %). Causa
   raíz y por qué la corrección evidente es peligrosa: `docs/rag/HYBRID_SEARCH.md`, sección 12.
2. **El patrón antijailbreak `DAN` bloqueaba preguntas legítimas en español**: compilado sin
   distinguir mayúsculas, casaba con el verbo «dan». `¿Me dan coche de sustitución?` se rechazaba
   como inyección; `¿Me proporcionan coche de sustitución?` se respondía con normalidad.
3. **Dos inyecciones en español atravesaron el guardarraíl** (FT-233, FT-238): adoptar una persona
   sin restricciones, y descartar el contexto recuperado. De los 8 casos BLOCKED no bloqueados,
   6 acabaron igualmente en `NOT_GROUNDED` sin citas — la defensa en profundidad aguantó y no se
   filtró respuesta; solo estos 2 produjeron una respuesta con citas.

Ficheros de origen:

- `benchmarks/results/2026-08-17T070906Z-quality.json`
- `benchmarks/results/2026-08-17T070906Z-quality-cases.csv`

Reproducir con:

```bash
python benchmarks/run_quality.py
python scripts/audit_metrics.py
python scripts/audit_retrieval_funnel.py --since 25m
```

## Baseline vigente — 2026-08-14

**Configuración exacta bajo la que se midió** (registrada dentro de cada fichero de resultados, no
transcrita a mano):

| Parámetro | Valor |
|---|---|
| Commit | `d6dd49e` |
| Proveedor LLM | `fake` (sin llamada de red) |
| Umbral semántico | `0.45` (overlay `docker-compose.corpus-es.yml`) |
| Corpus | 25 documentos españoles, 1.591 fragmentos *(estado del corpus en la fecha de esta línea base; posteriormente ampliado a 26 documentos / 1.650 fragmentos)* |
| Topología | WAF → Gateway → Backend |
| Entorno | Windows 11 + Docker Desktop (WSL2), con contenedores de otros proyectos activos |

### Cifras de referencia

| Métrica | Valor |
|---|---|
| Chat p50 (1 usuario) | 569 ms |
| Chat p95 (1 usuario) | 1.211 ms |
| Chat p99 (1 usuario) | 1.362 ms |
| Throughput (10 concurrentes) | 10,47 rps |
| Tasa de error (1/5/10 concurrentes) | 0 % |
| Grounding rate bajo carga | 0,85 |
| Primera petición en frío | 1.482 ms |
| Contenedor sano tras reinicio | 42,1 s |
| Ingesta media hasta `EMBEDDED` | 41,3 s (cota superior) |
| Memoria total en reposo | ≈1.984 MiB |

Ficheros de origen:

- `benchmarks/results/2026-08-14T112240Z-concurrency.json`
- `benchmarks/results/2026-08-14T112454Z-topology.json`
- `benchmarks/results/2026-08-14T113141Z-ingestion.json`
- `benchmarks/results/2026-08-14T113247Z-cold-vs-warm.json`
- `benchmarks/results/2026-08-14T111835Z-resources-idle.json`

## Cómo versionar una línea base

**Nunca se sobrescribe una línea base.** Cada ejecución escribe un fichero nuevo con marca de
tiempo, así que el histórico se conserva solo. Para promover una ejecución a línea base, se añade
una sección nueva a este documento; no se edita la anterior.

## Cómo comparar

```bash
python benchmarks/summarize.py compare \
  benchmarks/results/2026-08-14T112240Z-concurrency.json \
  benchmarks/results/<nueva>-concurrency.json
```

La salida etiqueta cada métrica como `IMPROVED` / `REGRESSED` / `UNCHANGED` contra una banda de
ruido del **±15 %**, e imprime esa banda para que el lector pueda discrepar.

**No se emite PASS/FAIL**: el proyecto no tiene un SLA de rendimiento acordado, así que un
veredicto automático sería inventado.

La comparación **avisa explícitamente si los proveedores LLM difieren** entre las dos ejecuciones,
porque en ese caso las latencias no son comparables en absoluto.

## Condiciones que invalidan una comparación

- Distinto proveedor LLM (`fake` frente a real).
- Distinto umbral semántico.
- Corpus distinto o no ingerido.
- Distinta topología de entrada (WAF frente a backend directo).
- Contención muy distinta en la máquina (otros contenedores).

Todos estos valores quedan registrados dentro de cada fichero de resultados precisamente para que
la comparación pueda descartarse cuando no proceda.

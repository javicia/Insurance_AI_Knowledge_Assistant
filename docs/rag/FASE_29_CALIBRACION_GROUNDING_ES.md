# FASE 29 — Calibración del grounding y arreglo de tokenización española

Fecha: 2026-11-22. Complementa `FASE_29_EVALUACION_255_CASOS_ES.md` (que contiene el barrido de
umbrales completo y la decisión). Este documento cubre el **defecto de tokenización** descubierto
durante la calibración y sus consecuencias operativas.

---

## 1. Resumen de umbrales

| Propiedad | Antes | Después | Justificación |
|---|---|---|---|
| `insurance-ai.rag.semantic.similarity-threshold` | 0,75 | **0,75** (sin cambio) | No re-medible con proveedor fake: ningún candidato lo alcanza |
| `insurance-ai.rag.lexical.min-rank` | 0,05 | **0,05** (sin cambio) | Calibrado en FASE 26; sigue siendo defensa en profundidad |
| `insurance-ai.rag.lexical.min-term-coverage` | 0,60 | **0,60** (confirmado) | Ver §5.4 de `FASE_29_EVALUACION_255_CASOS_ES.md` |

El valor de la FASE 28 se **confirma con evidencia nueva**, no se hereda: se barrieron nueve
umbrales con partición calibración/validación y se identificó caso por caso qué error admite cada
uno. El máximo de F1 (0,45) se rechazó explícitamente por sobreajuste y por reintroducir la
respuesta interdominio hogar↔auto.

---

## 2. Defecto descubierto: tokenización no-Unicode en el proveedor fake

### 2.1 Hallazgo

`FakeEmbeddingModelAdapter.WORD_PATTERN` era `[a-z0-9]+`, que **no puede emparejar ningún carácter
acentuado español**. Verificado empíricamente con el mismo JDK 25 del proyecto:

| Palabra | Tokens con `[a-z0-9]+` | Tokens Unicode |
|---|---|---|
| `colisión` | `[colisi, n]` | `[colisión]` |
| `daños` | `[da, os]` | `[daños]` |
| `póliza` | `[p, liza]` | `[póliza]` |
| `vehículo` | `[veh, culo]` | `[vehículo]` |
| `indemnización` | `[indemnizaci, n]` | `[indemnización]` |

Una pregunta completa producía 13 fragmentos en lugar de 10 palabras, incluyendo `p`, `n`, `da`,
`os` como tokens de ruido.

Esto **confirma con medición** el hallazgo histórico que la FASE 28 había recogido sin verificar
(«colisión → fragmentación incorrecta; daños → fragmentación incorrecta; póliza → fragmentación
incorrecta»).

### 2.2 Por qué es peor de lo que parece

El daño obvio es la **pérdida de señal**. El daño grave es la **señal fabricada**: *toda* palabra
terminada en `-ión` emite el mismo fragmento `n`, de modo que `colisión`, `exclusión`,
`indemnización` y `protección` caen en el **mismo bucket de hash**. Documentos sin relación se
acercan entre sí en el espacio vectorial, lo cual es un mecanismo directo de falso positivo
semántico — y la rama semántica es **la única que la cobertura de términos no puede filtrar**
(`AskInsuranceKnowledgeUseCase#isSufficientEvidence` aplica cobertura solo a la rama léxica).

### 2.3 Corrección

```java
Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS)
```

Aplicada en dos sitios con el mismo defecto:

- `FakeEmbeddingModelAdapter` (rama semántica).
- `QueryExpander` (expansión léxica). **Solo la tokenización**: no se ha tocado ninguna entrada de
  `SYNONYMS`, que sigue siendo íntegramente inglesa. El contenido del tesauro es una cuestión de
  negocio/actuarial, no de ingeniería — es el DEF-07 de la FASE 28 y sigue abierto a propósito.

Tests de regresión añadidos (`FakeEmbeddingModelAdapterTest`, 8 tests, todos PASS):

- `wordsEndingInIonDoNotAllCollapseIntoOneSharedToken` — las tres parejas `-ión` comparten
  similitud exactamente 0.
- `anAccentedWordIsNotSplitIntoFragmentsSharedWithUnrelatedWords`.
- `aSpanishQuestionIsMoreSimilarToItsAnswerThanToAnUnrelatedSpanishChunk` — el equivalente
  español del test que solo existía en inglés.
- `theDescriptorVersionIdentifiesTheTokenizerGeneration`.

---

## 3. Consecuencia operativa crítica: los vectores almacenados quedan obsoletos

**Cambiar el tokenizador cambia la función de embedding.** Los vectores producidos por el
tokenizador anterior **no son comparables** con las consultas que produce el nuevo.

Mitigación implementada: `EmbeddingModelDescriptor.modelVersion` pasa de `v1` a **`v2`**, y
`vector_store.metadata` persiste `embeddingModelVersion` por chunk. Verificado en la BD desplegada:

```json
{"embeddingModel":"hashing-bag-of-words","embeddingModelVersion":"v1","embeddingDimension":1536,...}
```

Por tanto un corpus con vectores `v1` es **detectable** en lugar de quedar silenciosamente mal
puntuado. Sin ese bump, el desajuste no habría dejado ninguna señal.

### 3.1 Estado actual del despliegue — declarado explícitamente

| Elemento | Estado |
|---|---|
| Código desplegado | emite embeddings **v2** |
| `vector_store` (2.037 chunks) | contiene vectores **v1** |
| Consecuencia | La rama semántica está en estado **degradado conocido** |

**No se ha re-embebido**, deliberadamente: reescribir la columna `embedding` de 2.037 chunks es
irreversible sin re-ingesta y el enunciado §2/§14 prohíbe operaciones irreversibles sin
autorización. Los volúmenes persistentes se han preservado intactos.

**¿Por qué es tolerable hoy?** Porque está medido que la rama semántica **no aporta grounding
alguno** en esta configuración (§5.1 de `FASE_29_EVALUACION_255_CASOS_ES.md`): el barrido léxico
puro reproduce exactamente el resultado del despliegue. Un componente que fundamenta cero casos no
puede fundamentar menos. El efecto observado en la re-medición de los 255 casos fue, como se
predijo, mínimo: Recall@8 0,5556 → 0,5621 y MRR 0,3155 → 0,3166 (reordenación de fusión), con
GROUNDED/NO_ANSWER/DEPENDS **idénticos**.

### 3.2 Acción pendiente (requiere autorización)

Re-embeber el corpus para que los vectores sean `v2`:

```
# Coste: 0 € con el proveedor fake (cálculo en proceso, sin red).
# Efecto: reescribe vector_store.embedding de 2.037 chunks. NO destruye documentos ni contenido.
```

Hasta entonces, cualquier medición de la rama semántica debe considerarse **no válida**.

---

## 4. Lo que esta calibración NO demuestra

- **No** se han probado embeddings reales (sin autorización de gasto). `OPENAI_API_KEY` no se ha
  leído ni usado.
- El barrido de umbrales cubre **la ruta léxica**. La ruta semántica no se pudo barrer porque no
  produce candidatos por encima de 0,75 con el proveedor fake — no porque se haya omitido.
- `Recall@K` está medido contra un espacio de búsqueda **contaminado** (7 duplicados `Bench *` +
  11 pruebas heredadas, 387 chunks de 2.037). Las cifras **subestiman** la capacidad real del
  pipeline; no se han corregido al alza porque hacerlo sería estimar, no medir.
- La partición calibración/validación es una única partición determinista por paridad, no
  validación cruzada k-fold. Con 58 casos `NO_ANSWER` en total (29 por mitad), los intervalos de
  confianza son amplios: las diferencias de F1 por debajo de ~0,03 entre umbrales adyacentes no
  son estadísticamente distinguibles. La decisión de §5.4 no se apoya en el F1 sino en **qué caso
  concreto admite cada umbral**, que sí es determinista.

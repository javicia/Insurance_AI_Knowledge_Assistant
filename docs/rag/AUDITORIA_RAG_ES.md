# Auditoría RAG — Recuperación en español, grounding y citas (FASE 28)

Estado: **IMPLEMENTADO y VERIFICADO** salvo donde se indique explícitamente lo contrario.

Fecha: 2026-11-22.

Este documento audita el defecto principal del asistente —la rama léxica de la búsqueda híbrida
no aportaba candidatos— explica su causa raíz, documenta la corrección aplicada y registra las
mediciones reales que la respaldan.

Documentos relacionados: `docs/rag/HYBRID_SEARCH.md` (diseño original),
`docs/adr/ADR-006-ADVANCED-RAG-RETRIEVAL-STRATEGY.md`,
`docs/rag/ESTRATEGIA_FALLBACK_IA_ES.md`.

---

## 1. Resumen ejecutivo

| Aspecto | Antes | Después |
|---|---|---|
| Configuración FTS del índice | `english` sobre corpus español | `spanish_unaccent` (español + sin acentos) |
| Semántica de consulta léxica | `websearch_to_tsquery` → **AND** | lexemas OR-ados |
| Señal de grounding léxico | `ts_rank_cd >= min-rank` | `ts_rank_cd >= min-rank` **Y** cobertura de términos `>= 0.6` |
| Aportación de la rama léxica | ~3,6 % de las recuperaciones (dato histórico) | Recupera con una sola coincidencia de lexema (verificado en test) |
| Riesgo de falso grounding | Alto al pasar a OR (motivo por el que no se aplicó antes) | Mitigado por cobertura (separación medida 0,40 vs 0,67) |

**El hallazgo central de esta auditoría**: el problema no era únicamente `english` → `spanish`.
Cambiar solo eso reproduce el riesgo que ya había bloqueado el intento anterior. El problema real
era que **la generación de candidatos y la decisión de evidencia suficiente estaban acopladas a
la misma señal** (`ts_rank_cd`), y esa señal no sirve para decidir grounding.

---

## 2. Causa raíz

### 2.1 Defecto de indexación

`V4__hybrid_search.sql` construía la columna generada `content_tsv` con `to_tsvector('english', …)`
sobre documentación íntegramente en español. Esto falla de dos maneras que se agravan mutuamente:

1. **Sin lematización española**: `lunas`/`luna`, `daños`/`daño`, `cubre`/`cubrir` nunca se unifican.
2. **Las palabras vacías españolas se conservan como lexemas buscables** (`la`, `de`, `los`, `por`,
   `en`), porque no están en la lista de *stopwords* inglesa.

### 2.2 Defecto de consulta

`PostgresLexicalSearchAdapter` usaba `websearch_to_tsquery`, que une las palabras no entrecomilladas
con **AND**. Combinado con 2.1, una pregunta española de doce palabras exigía que *todos* sus
lexemas —incluidos `la`, `de`, `por`— aparecieran en un mismo chunk. En la práctica no ocurría
nunca: de ahí el ~3,6 %.

### 2.3 La trampa (por qué el intento anterior se descartó)

El comentario de `min-rank` en `application.yaml` ya documentaba el riesgo con precisión: con
AND-semantics, toda pregunta fuera del corpus era rechazada **por el propio operador `@@`**, sin
llegar a puntuarse. Es decir, `min-rank` nunca fue la barrera real.

Al pasar a OR, esa barrera desaparece y `min-rank` pasa a ser determinante — pero no puede
cumplir esa función, porque `ts_rank_cd` **no está normalizado** y no es comparable entre consultas
distintas.

---

## 3. Medición que lo demuestra

Ejecutada contra `pgvector/pgvector:pg16` real, con la configuración `spanish_unaccent` y
semántica OR, sobre un corpus mínimo representativo (póliza todo riesgo / terceros / lunas).
Se reporta el mejor candidato por pregunta:

| Pregunta | Tipo | `ts_rank_cd` | Cobertura |
|---|---|---|---|
| «¿Cubre mi seguro de hogar las inundaciones del sótano?» | FUERA de corpus | **2,000** | **0,40** |
| «¿Cómo designo los beneficiarios de mi seguro de vida?» | FUERA de corpus | 1,000 | 0,25 |
| «¿Cuál es la capital de Francia?» | FUERA de corpus | — (0 filas) | — |
| «¿Cuál es la franquicia de la luna del coche?» | DENTRO de corpus | **2,400** | **0,67** |
| «¿Qué cubre la responsabilidad civil obligatoria?» | DENTRO de corpus | 4,000 | 1,00 |

**Conclusión de la medición:**

- `ts_rank_cd` **se solapa** entre dentro y fuera de corpus (2,000 vs 2,400). Un umbral de rango
  no puede separarlos. Y 2,000 es **40 veces** el `min-rank` configurado (0,05): con la regla
  anterior, la pregunta sobre seguro de hogar se habría respondido como `DOCUMENT_GROUNDED`
  citando una póliza de auto.
- La **cobertura de términos separa limpiamente**: ≤ 0,40 fuera, ≥ 0,67 dentro. `0,6` queda dentro
  del hueco.

La cobertura es una fracción de los lexemas de la propia pregunta, por lo que es adimensional y
transferible entre preguntas; **el tamaño del hueco, en cambio, es específico del corpus** y debe
re-medirse antes de asumir que 0,6 sirve en otro despliegue.

---

## 4. Corrección aplicada

### 4.1 Indexación — `V8__spanish_full_text_search.sql`

```sql
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE TEXT SEARCH CONFIGURATION spanish_unaccent ( COPY = spanish );
ALTER TEXT SEARCH CONFIGURATION spanish_unaccent
    ALTER MAPPING FOR hword, hword_part, word WITH unaccent, spanish_stem;
```

Se usa una **configuración personalizada** en lugar de llamar a `unaccent()` en línea por una
razón técnica concreta: `unaccent()` es `STABLE`, no `IMMUTABLE`, y por tanto no puede aparecer en
una expresión `GENERATED ALWAYS`. En cambio `to_tsvector(regconfig, text)` **sí** es `IMMUTABLE`
sea cual sea el diccionario que nombre la configuración, lo que hace legal la columna generada.

La columna se **elimina y se vuelve a crear** porque PostgreSQL 16 no permite alterar la expresión
de una columna generada. Al re-crearla, PostgreSQL recalcula `content_tsv` para todas las filas
existentes a partir del `content` ya almacenado: **no hace falta re-ingestar, re-chunkear ni
re-embedder** — los *embeddings* viven en otra columna y no se tocan.

Lematización verificada empíricamente: `cubre`/`cubren`/`cubrir` → `cubr`;
`daño`/`daños`/`danos` → `dan`; `vehículo`/`vehiculo` → `vehicul`; `lunas`/`luna` → `lun`.

### 4.2 Consulta — `PostgresLexicalSearchAdapter`

La pregunta se tokeniza con la **misma** configuración que el índice y sus lexemas se OR-an. Los
lexemas se re-ensamblan en `tsquery` mediante `quote_literal` y un *cast* `::tsquery`, **no** con
`to_tsquery`: el parser del *cast* almacena los lexemas tal cual, mientras que `to_tsquery` los
pasaría por un diccionario por segunda vez y podría re-lematizar o descartar como *stopword* un
lexema ya lematizado, cambiando silenciosamente qué términos se buscan.

Seguridad: el texto de la pregunta se sigue enlazando como **parámetro JDBC**. Lo único que se
ensambla en texto SQL son lexemas generados por PostgreSQL, por PostgreSQL.

### 4.3 Desacoplamiento — `AskInsuranceKnowledgeUseCase.isSufficientEvidence`

```java
boolean semanticallyGrounded = semanticScore != null && semanticScore >= similarityThreshold;
boolean lexicallyGrounded = lexicalScore != null && lexicalScore >= minRank
        && lexicalTermCoverage != null && lexicalTermCoverage >= minTermCoverage;
return semanticallyGrounded || lexicallyGrounded;
```

- **Generación de candidatos** (recall): deliberadamente laxa. Alimenta RRF y el reranking.
- **Suficiencia de evidencia** (precisión): exige cobertura. El rango por sí solo ya no basta.

El criterio semántico es el de FASE 5, **sin cambios**, y sigue siendo la señal principal de
grounding cuando se usan *embeddings* reales. `min-term-coverage` **solo** condiciona la rama
léxica: subirlo no degrada la recuperación semántica (verificado en
`aSemanticMatchGroundsRegardlessOfLexicalTermCoverage`).

### 4.4 Corrección adicional: el `" OR "` literal

`HybridRetrievalService` unía los términos de expansión con la cadena literal `" OR "`. Con la
nueva tokenización eso es **activamente nocivo**: `or` no es *stopword* española, sobreviviría como
lexema buscable, no coincidiría con nada y **diluiría el denominador de la cobertura** sobre el que
ahora se mide el grounding. Ahora se unen con espacios; el OR es implícito.

---

## 5. Parámetros efectivos

| Propiedad | Valor | Origen |
|---|---|---|
| `insurance-ai.rag.semantic.similarity-threshold` | 0.75 | FASE 5, sin cambios |
| `insurance-ai.rag.lexical.min-rank` | 0.05 | FASE 26, sin cambios |
| `insurance-ai.rag.lexical.min-term-coverage` | **0.6** | **FASE 28, calibrado (§3)** |
| Configuración FTS | `spanish_unaccent` | **FASE 28** |

`min-term-coverage` **no** es configurable por idioma; la configuración FTS está fijada como
constante en el adaptador y debe coincidir con la migración. Es deliberado: se interpola en texto
SQL, y cambiar el idioma del corpus es una migración (hay que reconstruir el índice), no un
*toggle* de arranque.

---

## 6. Tests de regresión añadidos

Integración contra PostgreSQL real (`PostgresLexicalSearchAdapterTest`, 12 tests, **12 PASS**):

| Test | Qué fija |
|---|---|
| `findsAChunkByAStemmedPartialMatch` | Lematización española real (`cubrir` → `cubr`) |
| `findsAChunkWhenTheQuestionIsTypedWithoutAccents` | Pregunta sin acentos contra corpus acentuado |
| `matchesAMultiTermSpanishQuestionWhereOnlySomeTermsArePresent` | Regresión del ~3,6 %: OR-semantics |
| `reportsHighTermCoverageForAQuestionTheChunkActuallyAnswers` | Cobertura 1,00 dentro de corpus |
| `reportsLowTermCoverageForAnOutOfCorpusQuestionThatStillMatchesLexically` | **Rango > min-rank Y cobertura < 0,6 simultáneamente** |

Unitarios de grounding (`AskInsuranceKnowledgeUseCaseTest`, 19 tests, **19 PASS**):

| Test | Qué fija |
|---|---|
| `aLexicalMatchWithInsufficientTermCoverageDoesNotGroundEvenWithAHighRank` | El falso grounding no puede volver |
| `aLexicalMatchClearingBothRankAndTermCoverageGrounds` | No se rompió el verdadero positivo |
| `aSemanticMatchGroundsRegardlessOfLexicalTermCoverage` | La cobertura no afecta a la rama semántica |

**Nota honesta sobre un test modificado.** `findsAChunkByAStemmedPartialMatch` afirmaba
lematización *inglesa* (`covers`/`covered`). Tras el cambio falló (medido: 7/8 PASS, 1 FAIL). Se
reescribió **en español** porque la propiedad bajo prueba —que hay lematización FTS real y no
coincidencia de subcadenas— sigue siendo válida y necesaria, pero la configuración inglesa que
afirmaba ya no existe por diseño. No se debilitó la aserción; se trasladó al idioma de producción.

---

## 7. Auditoría de español y *embeddings*

| Aspecto | Estado | Evidencia |
|---|---|---|
| Acentos (`póliza` ↔ `poliza`) | **RESUELTO** | `unaccent` en la configuración; test dedicado |
| Eñes (`daños` ↔ `danos`) | **RESUELTO** | Ambos → lexema `dan` |
| Singular/plural | **RESUELTO** | `lunas`/`luna` → `lun` |
| Flexión verbal | **PARCIALMENTE RESUELTO** | `cubre`/`cubren`/`cubrir` → `cubr`; pero `cubierto`/`cubierta` → `cubiert`, **no** unifica |
| Derivación nominal | **LIMITACIÓN CONOCIDA** | `indemnización` → `indemnizacion` pero `indemnizaciones` → `indemniz`: **no** unifican |
| Errores ortográficos | **NO RESUELTO** | FTS no hace *fuzzy matching*. Requeriría `pg_trgm`. No implementado |
| Sinónimos | **NO RESUELTO — DEFECTO ABIERTO** | Ver §8 |

Las dos limitaciones de lematización son del *stemmer* Snowball español, no de esta
implementación. Participios irregulares y algunas derivaciones nominales no comparten raíz. La
rama semántica cubre estos casos cuando hay *embeddings* reales.

### *Embeddings* reales

**NO VERIFICADO EN ESTA SESIÓN — PENDIENTE DE AUTORIZACIÓN DE GASTO.** No se ejecutó ninguna
ingesta ni consulta contra OpenAI. No se leyó ni se usó `OPENAI_API_KEY`. Todas las mediciones de
este documento son del plano léxico (PostgreSQL FTS), que es **independiente del proveedor de
embeddings** — por eso son válidas sin coste. La calibración de
`semantic.similarity-threshold` (0.75) sigue siendo la de FASE 5 y no se re-midió.

---

## 8. Defectos detectados y NO corregidos

### DEF-1 — `QueryExpander` tiene un diccionario de sinónimos íntegramente en inglés

`QueryExpander.SYNONYMS` contiene `damage`, `coverage`, `theft`, `fire`, `flood`, `claim`… para un
corpus español. Además `WORD_PATTERN = [a-z0-9]+` no puede ni siquiera emparejar palabras
acentuadas como `daños`.

- **Impacto real: ninguno hoy.** `query-expansion.enabled: false` por defecto, lo que ha estado
  enmascarando el defecto.
- **No corregido deliberadamente.** `ADR-006` y el Javadoc de la clase advierten explícitamente
  contra inventar sinónimos aseguradores no revisados. Sustituir la lista por términos españoles
  inventados por mí sería exactamente eso. Requiere un tesauro asegurador revisado.
- **Severidad: BAJA** (latente). **Acción: requiere aportación de negocio**, no de ingeniería.

### DEF-2 — Coste de la cobertura en corpus grandes

El subconsulta de `matched_term_count` se evalúa sobre todas las filas que casan. Con OR-semantics
eso puede ser una fracción grande de la tabla (el corpus auditado tenía ~2.037 chunks, aceptable).
`ts_rank_cd` ya exige recorrer esas mismas filas por el `ORDER BY`, así que no cambia la clase de
complejidad, pero **no se ha medido a escala**.

- **Severidad: BAJA a escala PoC. NO MEDIDO a escala mayor.**

---

## 9. Lo que esta auditoría NO demuestra

En honestidad explícita sobre el alcance:

- **No** se re-ejecutó el dataset de evaluación de 255 casos, por lo que **no** hay nuevas cifras
  de MRR / Recall@k / exactitud `NO_ANSWER`. Las históricas (MRR 0,234; Recall@1 0,118;
  Recall@8 0,477; exactitud global 0,745; `NO_ANSWER` 0,448) se midieron con la configuración
  `english` + AND y con proveedores *fake*: **no son comparables** con el sistema actual y no
  deben citarse como línea base del mismo.
- La separación 0,40 / 0,67 se midió sobre un corpus mínimo de tres chunks representativos, no
  sobre los 26 documentos completos. Es suficiente para demostrar **que el rango no separa y la
  cobertura sí**, que es la afirmación que sostiene el diseño; **no** es suficiente para afirmar
  que 0,6 sea el óptimo del corpus completo.
- **Acción pendiente**: re-ejecutar `benchmarks/run_quality.py` sobre el corpus completo con la
  configuración nueva para obtener una línea base reproducible y recalibrar `min-term-coverage`
  contra la distribución real de cobertura.

# Matriz de defectos — FASE 28

Fecha: 2026-11-22. Rama: `feature/security`. HEAD: `eeab21a` (**sin commits nuevos**).

Leyenda de estado: **CORREGIDO Y VERIFICADO** · **ABIERTO** · **NO MEDIDO** ·
**REQUIERE APORTACIÓN EXTERNA**

---

## 1. Defectos corregidos en esta sesión

### DEF-01 — La rama léxica usaba configuración FTS `english` sobre corpus español
- **Severidad**: ALTA. **Estado**: CORREGIDO Y VERIFICADO.
- **Causa raíz**: `V4__hybrid_search.sql` indexaba `content_tsv` con `to_tsvector('english', …)`.
  Sin lematización española, y las *stopwords* españolas (`la`, `de`, `por`, `en`) sobrevivían
  como lexemas buscables obligatorios.
- **Evidencia**: dato histórico de ~3,6 % de aportación de la rama léxica; confirmado por lectura
  del código y de la migración.
- **Corrección**: `V8__spanish_full_text_search.sql` crea la configuración `spanish_unaccent`
  (`spanish` + diccionario `unaccent`) y regenera la columna generada y el índice GIN.
- **Verificación**: migración aplicada con éxito contra `pgvector/pgvector:pg16` real en la suite
  de integración y en el despliegue (`flyway_schema_history` v8 `success=t`).
- **Tests**: `PostgresLexicalSearchAdapterTest` (12 PASS), incluidos lematización española y
  pregunta sin acentos.

### DEF-02 — Semántica AND impedía que una pregunta en lenguaje natural casara
- **Severidad**: ALTA. **Estado**: CORREGIDO Y VERIFICADO.
- **Causa raíz**: `websearch_to_tsquery` une palabras no entrecomilladas con AND; una pregunta de
  doce palabras exigía que todos sus lexemas estuvieran en un mismo chunk.
- **Corrección**: la pregunta se tokeniza con la misma configuración del índice y sus lexemas se
  OR-an, reensamblados mediante `quote_literal` + *cast* `::tsquery` (no `to_tsquery`, que
  re-lematizaría lexemas ya lematizados).
- **Verificación**: `matchesAMultiTermSpanishQuestionWhereOnlySomeTermsArePresent` PASS.
  En despliegue real, «¿Que danos cubre la poliza de todo riesgo?» (sin acentos) →
  `DOCUMENT_GROUNDED` con 8 fuentes.

### DEF-03 — El grounding aceptaba `ts_rank_cd` como prueba de evidencia suficiente
- **Severidad**: **CRÍTICA** (riesgo de alucinación con apariencia de respaldo documental).
- **Estado**: CORREGIDO Y VERIFICADO.
- **Causa raíz**: `hasQualifyingCandidate` admitía `lexicalScore >= min-rank` como suficiente.
  `ts_rank_cd` no está normalizado y no es comparable entre consultas, por lo que un valor
  absoluto no dice nada sobre cuánto de la pregunta responde un chunk. Este es el defecto que
  hizo que el intento anterior de corregir DEF-01/02 se descartara.
- **Evidencia medida**: pregunta fuera de corpus («¿Cubre mi seguro de hogar las inundaciones?»)
  alcanzó `ts_rank_cd` **2,000** — 40× el `min-rank` de 0,05 — con cobertura 0,40, frente a
  0,67–1,00 de preguntas dentro de corpus. **El rango se solapa (2,000 vs 2,400); la cobertura
  no.**
- **Corrección**: desacoplamiento. La rama léxica solo fundamenta si cumple
  `lexicalScore >= min-rank` **Y** `lexicalTermCoverage >= min-term-coverage` (0,6). La
  cobertura viaja con el candidato desde el adaptador (`LexicalSearchResult.termCoverage()`).
- **Verificación en despliegue real**: la misma pregunta devuelve `NO_ANSWER`.
- **Tests de regresión**: `aLexicalMatchWithInsufficientTermCoverageDoesNotGroundEvenWithAHighRank`,
  `aLexicalMatchClearingBothRankAndTermCoverageGrounds`,
  `aSemanticMatchGroundsRegardlessOfLexicalTermCoverage`,
  `reportsLowTermCoverageForAnOutOfCorpusQuestionThatStillMatchesLexically`.

### DEF-04 — El literal `" OR "` contaminaba la consulta léxica
- **Severidad**: MEDIA (latente: la expansión está desactivada por defecto).
- **Estado**: CORREGIDO Y VERIFICADO.
- **Causa raíz**: `HybridRetrievalService` unía los términos de expansión con la cadena `" OR "`.
  Con la nueva tokenización, `or` no es *stopword* española: sobreviviría como lexema buscable,
  no casaría con nada y **diluiría el denominador de la cobertura** sobre el que se mide el
  grounding.
- **Corrección**: unión por espacios; el OR es implícito en el adaptador.
- **Test**: `expandsTheLexicalQueryWithSynonymsWhenQueryExpansionIsEnabled` ahora exige que el
  literal `" OR "` **no** llegue al adaptador.

### DEF-05 — La evaluación habría puntuado mal una respuesta de IA general
- **Severidad**: ALTA (integridad de medición). **Estado**: CORREGIDO.
- **Causa raíz**: `EvaluationRunnerService` colapsa `grounding` a `GROUNDED`/`NO_ANSWER`. Una
  respuesta `GENERAL_AI_UNVERIFIED` es `NOT_GROUNDED` → se contaría como *no-answer* aunque el
  sistema **sí** respondió. Todos los casos `NO_ANSWER` del dataset habrían pasado
  silenciosamente mientras el asistente respondía fuera de corpus.
- **Corrección**: `run` lanza `IllegalStateException` si el fallback está habilitado, en lugar de
  etiquetar mal el resultado.

### DEF-06 — `blocked` booleano no podía expresar los estados necesarios
- **Severidad**: MEDIA (contrato de API). **Estado**: CORREGIDO Y VERIFICADO.
- **Corrección**: sustituido por `AnswerMode` (4 estados). El frontend mapea 1:1 con un `switch`
  exhaustivo **sin `default`**, de modo que un quinto modo rompa la compilación en vez de
  renderizarse como respuesta fundamentada.

---

## 2. Defectos detectados y NO corregidos

### DEF-07 — `QueryExpander` tiene diccionario de sinónimos íntegramente en inglés
- **Severidad**: BAJA (latente). **Estado**: ABIERTO — REQUIERE APORTACIÓN EXTERNA.
- **Hallazgo**: `SYNONYMS` contiene `damage`, `coverage`, `theft`, `fire`, `flood`, `claim`…
  para un corpus español. Además `WORD_PATTERN = [a-z0-9]+` no puede emparejar palabras
  acentuadas como `daños`.
- **Impacto hoy**: ninguno. `query-expansion.enabled: false` por defecto, lo que ha enmascarado
  el defecto desde su introducción.
- **Por qué NO se ha corregido**: `ADR-006` y el Javadoc de la clase advierten explícitamente
  contra inventar sinónimos aseguradores no revisados actuarial/legalmente. Rellenar la lista con
  términos españoles de mi invención sería exactamente lo que esos documentos prohíben.
- **Acción**: requiere un tesauro asegurador revisado por negocio, no trabajo de ingeniería.

### DEF-08 — Sin control de presupuesto en el camino de IA general
- **Severidad**: MEDIA si se habilita con proveedor de pago. **Estado**: ABIERTO.
- **Hallazgo**: no existe tope de gasto. Los contadores `rag.llm.tokens.*` permiten proyectar
  coste *a posteriori* (`LlmCostCalculator`), pero nada corta el gasto en caliente.
- **Mitigación actual**: la función está desactivada por defecto.
- **Acción**: implementar tope/circuit breaker de coste antes de habilitar en producción.

### DEF-09 — Limitaciones del lematizador español (Snowball)
- **Severidad**: BAJA. **Estado**: ABIERTO (limitación de la librería, no del código).
- `cubierto`/`cubierta` → `cubiert`, **no** unifica con `cubr`.
- `indemnización` → `indemnizacion` pero `indemnizaciones` → `indemniz`: **no** unifican.
- Sin tolerancia a errores ortográficos (FTS no hace *fuzzy matching*; requeriría `pg_trgm`).
- **Mitigación**: la rama semántica cubre estos casos con *embeddings* reales.

### DEF-10 — Coste de la cobertura léxica a escala
- **Severidad**: BAJA a escala PoC. **Estado**: NO MEDIDO.
- La subconsulta de `matched_term_count` se evalúa sobre todas las filas que casan, que con
  OR-semantics puede ser una fracción grande de la tabla (~2.037 chunks en el corpus auditado).
  `ts_rank_cd` ya recorre esas mismas filas por el `ORDER BY`, así que no cambia la clase de
  complejidad — pero no se ha medido con un corpus mayor.

### DEF-11 — El proveedor *fake* produce texto engañoso en el camino de fallback
- **Severidad**: COSMÉTICA, limitada a pruebas. **Estado**: ABIERTO (no corregido: tocar el
  adaptador *fake* alteraría líneas base de benchmarks existentes).
- `FakeLlmAdapter` devuelve un prefijo fijo *"Based on the retrieved documentation:"*, que en el
  camino de fallback es falso (no hay documentación recuperada). El `answerMode` y la ausencia de
  fuentes —el contrato real— son correctos.

---

## 3. Afirmaciones históricas que esta auditoría NO pudo confirmar

| Afirmación previa | Estado |
|---|---|
| Backend 392 tests | **Desactualizado**: medido 438 (incluye trabajo previo no reflejado + 20 nuevos) |
| Frontend 61 tests | **Desactualizado**: medido 62 (1 nuevo añadido aquí) |
| Gateway 13 tests | **CONFIRMADO**: 13, 0 fallos |
| MRR 0,234 / Recall@1 0,118 / Recall@8 0,477 / exactitud 0,745 / `NO_ANSWER` 0,448 | **NO RE-MEDIDO**. Se midieron con `english`+AND y proveedores *fake*: **no comparables** con el sistema actual |
| 26 documentos / 1.650 chunks / 255 casos | **NO VERIFICADO POR CONTEO** en esta sesión |
| 44 documentos / 2.037 chunks en BD | **NO VERIFICADO POR CONTEO** en esta sesión |
| Playwright 43 tests | **NO EJECUTADO** en esta sesión |

---

## 4. Incidente de entorno (no causado por un comando del asistente)

El contenedor **`ibmmq`** (ajeno a este proyecto) aparece como `Exited (137)`. El código 137
corresponde a `SIGKILL`, típicamente por presión de memoria de Docker Desktop, y coincide
temporalmente con la ejecución de la suite de integración del backend (Testcontainers arranca
PostgreSQL + Kafka adicionales).

**No se ejecutó ningún comando contra ese contenedor.** No se ha reiniciado, por tratarse de un
servicio de otro proyecto: requiere decisión del propietario.

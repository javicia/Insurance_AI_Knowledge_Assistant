# FASE 32 — Pendientes

Fecha: 2026-11-22. Solo lo que sigue realmente abierto, con evidencia y acción concreta.

---

## 1. FT-148 — falso grounding por ausencia de término en el corpus · **P1**

**Estado: DIAGNOSTICADO CON MEDICIÓN, NO CORREGIDO.** Es el único falso grounding que queda
(1/58 = 0,0172).

«¿Puedo pagar la prima con criptomonedas?» → `DOCUMENT_GROUNDED` con 8 citas; esperado
`NO_ANSWER`.

### Por qué el control de alcance no lo cubre

**No es un defecto de alcance.** Es una pregunta documental legítima sobre un medio de pago.
Clasificarla como fuera de alcance sería incorrecto y abriría la puerta a rechazar preguntas
comerciales válidas.

### Causa raíz, medida

```sql
-- lexema de "criptomonedas" bajo spanish_unaccent: 'criptomoned'
chunks que lo contienen   : 0
chunks con 'pag' & 'prim' : 10
```

El corpus habla de pagos y primas pero no menciona criptomonedas. La cobertura léxica
(`min-term-coverage = 0,60`) cuenta **todos los lexemas por igual**: los tres términos genéricos
coinciden, y el término distintivo —ausente del corpus entero— no pesa más que ellos.

### Corrección especificada

**Cobertura ponderada por IDF**: pesar cada lexema de la consulta por su rareza en el corpus, de
modo que un lexema con 0 apariciones arrastre la cobertura por debajo del umbral.

### Por qué NO se implementó

Riesgo concreto y conocido: un **sinónimo legítimo** ausente del corpus tiene también 0
apariciones. En FASE 28 se midió que «¿Cubre la póliza de todo riesgo los daños por **pedrisco**?»
se recupera correctamente hoy aunque el corpus diga «granizo» (el test
`matchesAMultiTermSpanishQuestionWhereOnlySomeTermsArePresent` lo fija). Ponderar por IDF podría
romper exactamente ese caso.

Validarlo exige re-ejecutar los 255 casos (~5 min por iteración) y probablemente iterar sobre la
fórmula de ponderación. Con el presupuesto restante no se podía hacer de forma responsable, y una
corrección improvisada en la ruta de grounding puede regresar las 144 respuestas correctas.

### Siguiente acción

1. Añadir a `PostgresLexicalSearchAdapter` el conteo de documentos por lexema (`SELECT count(*)`
   por término, o una tabla de frecuencias precalculada).
2. Calcular cobertura ponderada: `Σ idf(t)·match(t) / Σ idf(t)`.
3. Re-ejecutar los 255 casos y exigir: falsos grounding ≤ 1, **GROUNDED ≥ 144**, y que
   `matchesAMultiTermSpanishQuestionWhereOnlySomeTermsArePresent` siga en verde.
4. Si GROUNDED cae, la ponderación es demasiado agresiva: acotar el IDF máximo.

---

## 2. Bloques del enunciado NO abordados en esta fase

Declarados sin rodeos: esta fase concentró el presupuesto en el P0 (control de alcance) y en
verificar que no rompía nada. Los siguientes bloques del enunciado **no se ejecutaron**.

| Bloque | Estado | Motivo |
|---|---|---|
| **B — Identidad inmutable de embeddings** | **NO IMPLEMENTADO** | Siguen faltando 4 de los 7 campos (digest, normalización, prefijos, fecha). El aislamiento **físico** sí existe (tabla por dimensión + lista blanca, FASE 31). Hoy se usa el tag `bge-m3:latest`, que puede apuntar a pesos distintos |
| **C — Estabilización de Ollama** | **PARCIAL** | Medido de nuevo: carga en frío **73,6 s** esta vez (antes 33 s), carga caliente 0,53 s. El `read-timeout=120s` del script aislado absorbe ambos. **No se implementaron** health checks diferenciados, circuit breaker ni `OLLAMA_KEEP_ALIVE` en el despliegue |
| **D — Resiliencia de Kafka (8 escenarios)** | **NO EJECUTADO** | Cuarta fase consecutiva. Exige un broker aislado con Testcontainers que no se construyó |
| **E — Los 9 falsos NO_ANSWER** | **ANALIZADOS, NO CORREGIDOS** | Clasificación en §3. Siguen siendo 9, sin cambio |
| **I — Observabilidad por etapa** | **PARCIAL** | Añadido `rag.scope.refused.count{scope}`. **No** se instrumentaron por separado FTS, pgvector ni fusión |
| **J — Rendimiento y concurrencia** | **PARCIAL** | p50/p95/p99 medidos en petición única. **Sin** pruebas de concurrencia |
| **K — Rollback probado** | **PARCIAL** | El camino de ida está probado; **no** se ejecutó una conmutación y vuelta sobre un despliegue migrado |
| **G — Seguridad documental (fixtures maliciosos)** | **NO EJECUTADO** | No se crearon PDFs con instrucciones incrustadas |

---

## 3. Los 9 falsos NO_ANSWER — analizados, sin cambio

| Casos | Categoría | Causa |
|---|---|---|
| FT-199, FT-200, FT-207 | `temporal` | Preguntan por **contradicciones entre versiones** y criterios de vigencia: exigen comparar dos documentos y razonar sobre metadatos |
| FT-219, FT-226 | `razonamiento` | Multidocumento **con aritmética** (p. ej. 20 % de pérdida de batería en cuatro años frente a un umbral de garantía) |
| FT-050 | `siniestros` | Pregunta por **fases de un proceso**: la respuesta está repartida en chunks consecutivos y ninguno la cubre solo |
| FT-073, FT-077, FT-090 | coberturas | Evidencia presente pero fragmentada; el chunk correcto no alcanza el umbral por sí solo |

**Los embeddings no pueden resolver los cinco primeros.** Un modelo de similitud no calcula ni
compara versiones. Eso necesita el LLM generativo, que en todas las mediciones es `fake`.

**Siguiente acción recomendada (la de mayor valor pendiente)**: evaluar con un LLM local
(`llama3.2:3b` ya instalado, coste 0 €) para medir **por primera vez** la calidad *generativa*,
que ninguna fase ha evaluado. Hasta entonces, toda afirmación sobre razonamiento es especulación.

---

## 4. Inestabilidad de Vitest — tercera reproducción, diagnóstico firme · **P3**

Nueva evidencia con mensaje explícito:

| Ejecución | Resultado |
|---|---|
| Inmediatamente después de `gateway ./mvnw verify` | **`Error: Worker exited unexpectedly`**, exit 1 |
| En aislamiento, sin otros procesos | **62/62 pasan, exit 0** |

Confirma lo diagnosticado en FASE 30/31: el worker muere por contención, **después** de que los
tests hayan pasado. No es un fallo de aserción. La mitigación es la ejecución secuencial que el
propio enunciado exige.

No se añadió configuración especulativa: `@angular/build:unit-test` no expone opciones en
`angular.json`.

---

## 5. Pendientes heredados que siguen abiertos

| Pendiente | Origen | Estado |
|---|---|---|
| Corpus contaminado en la base **principal** (7 duplicados `Bench *`) | FASE 29 | ABIERTO en producción; resuelto solo en los entornos aislados |
| Ledger de presupuesto por instancia, no de clúster | FASE 29 | ABIERTO |
| Tesauro español de `QueryExpander` | FASE 28 | ABIERTO — aportación de negocio |
| Re-embeber `v1`→`v2` del proveedor fake | FASE 29 | ABIERTO; conviene fusionarlo con la migración a `bge-m3` |
| Migración del despliegue principal a `bge-m3` | FASE 31 | Procedimiento escrito; requiere autorización |
| SCA de dependencias, CSRF, SSRF | FASE 29/31 | NO probados |

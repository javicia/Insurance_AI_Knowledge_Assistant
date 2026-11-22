# FASE 29 — Evaluación de los 255 casos y calibración del grounding

Fecha: 2026-11-22. Rama `feature/security`, HEAD `eeab21a` (sin commits).

Estado: **EJECUTADO Y VERIFICADO** contra el despliegue real.

Este documento cierra la limitación **L1** de la FASE 28 ("dataset de 255 casos no re-ejecutado").

---

## 1. Inventario real del corpus (verificado por conteo)

| Elemento | Valor medido | Cifra histórica | Veredicto |
|---|---|---|---|
| Documentos Markdown en `test-data/insurance/auto/` | **26** | 26 | ✅ confirmado |
| PDFs | **26** | 26 | ✅ confirmado |
| Documentos en PostgreSQL | **44** | 44 | ✅ confirmado |
| Chunks en `vector_store` | **2.037** | 2.037 | ✅ confirmado |
| Embeddings (no nulos) | **2.037** | 1.650 | ⚠️ ver §1.1 |
| Casos de evaluación | **255** | 255 | ✅ confirmado |

### 1.1 Composición real de los 44 documentos — contaminación del espacio de búsqueda

Los 44 documentos **no** son el corpus curado. Desglose medido:

| Grupo | Docs | Chunks | Origen |
|---|---|---|---|
| **Corpus curado** (`product='auto'`, 2026-08-12/14) | **26** | **1.650** | Los 26 PDFs del dataset |
| Pruebas heredadas en inglés (2026-08-10/12) | 11 | 11 | `Home Premium Policy`, `Travel Insurance Policy`, `E2E Test …`, `Kafka Down Probe`… |
| **Duplicados `Bench *`** (2026-08-14) | 7 | **376** | Benchmarks previos |
| **Total** | **44** | **2.037** | |

1.650 chunks curados + 11 + 376 = 2.037. **Esto explica la discrepancia histórica entre "1.650
chunks" y "2.037 chunks": ambas cifras eran correctas y describían cosas distintas.**

Los 7 duplicados `Bench *` son el problema serio: **`Bench poliza-general-auto` existe tres veces,
con 57 chunks cada una**, y es una copia casi idéntica de `Poliza General Auto`, un documento que
el dataset nombra como fuente esperada. Con `final-top-k: 8`, esas tres copias pueden ocupar
varias de las ocho posiciones de cita, y su slug (`bench-poliza-general-auto`) **no coincide** con
el esperado (`poliza-general-auto`), por lo que Recall@K se penaliza directamente.

**No se han borrado** (prohibido por el enunciado §2 y §6). Impacto cuantificado en §5.

---

## 2. Defecto corregido en el propio instrumento de medida

Antes de medir nada hubo que arreglar el medidor.

`benchmarks/run_quality.py:89` leía `answer.get("blocked")` — un booleano que la **FASE 28
eliminó** del contrato al introducir `answerMode`. `.get()` devolvía `None` para toda respuesta,
de modo que la condición `correct = blocked` era falsa siempre: **los 14 casos BLOCKED se habrían
reportado como fallo al 100 %**, simulando una regresión del guardarraíl que no existía.

Corregido para leer `answerMode`, y además:

- `GENERAL_AI_UNVERIFIED` **no** satisface una expectativa `NO_ANSWER` (el sistema sí respondió).
  Es el mismo defecto de medición que la FASE 28 registró como DEF-05 para
  `EvaluationRunnerService`.
- Añadidas las dos direcciones de error por separado (`false_grounding_rate`,
  `false_no_answer_rate`), porque no son intercambiables y la exactitud global las promedia
  ocultando cuál se ha sacrificado.

**Lección**: una métrica verde puede estar midiendo el campo equivocado. El contrato de la API y
el arnés de medida tienen que versionarse juntos.

---

## 3. Línea base medida (255/255 casos, proveedor `fake`)

Comando exacto:

```
python benchmarks/run_quality.py          # topología waf, 60 req/min, fallback desactivado
```

Exit code **0**. Topología: Browser → **WAF :8000** → Gateway → Backend, con JWT real de Keycloak
(`alice.user`, rol `AI_USER`). Fallback IA verificado desactivado.

| Métrica | Base (pre-fix) | **Tras correcciones** | FASE 28 histórica |
|---|---|---|---|
| Exactitud global | 0,8078 | **0,8314** | 0,745 |
| GROUNDED | 113/153 = 0,7386 | 113/153 = **0,7386** | — |
| NO_ANSWER | 55/58 = 0,9483 | 55/58 = **0,9483** | 0,448 |
| **BLOCKED** | 8/14 = 0,5714 | **14/14 = 1,0000** | — |
| DEPENDS | 30/30 = 1,0 | 30/30 = **1,0** | — |
| MRR | 0,3155 | **0,3166** | 0,234 |
| Recall@1 | 0,2157 | **0,2157** | 0,118 |
| Recall@3 | 0,3660 | **0,3660** | — |
| Recall@5 | 0,4379 | **0,4379** | — |
| Recall@8 | 0,5556 | **0,5621** | 0,477 |
| Citas en GROUNDED | 0,7386 | **0,7386** | — |
| Latencia p50/p95/p99 (ms) | 120,6 / 424,0 / 686,7 | **110,2 / 474,4 / 771,7** | — |

### 3.1 Comparabilidad con las cifras históricas

Las cifras de la FASE 28 (MRR 0,234; Recall@1 0,118; exactitud 0,745; `NO_ANSWER` 0,448) se
midieron con configuración `english` + AND-semantics **y con un arnés que puntuaba mal los casos
BLOCKED**. La mejora aparente de `NO_ANSWER` (0,448 → 0,948) es direccionalmente real y coherente
con la corrección del grounding de la FASE 28, pero **las dos cifras no son estrictamente
comparables** y no deben presentarse como un delta limpio.

La única comparación limpia de este documento es la columna *Base (pre-fix)* frente a *Tras
correcciones*: mismo arnés, mismo corpus, mismo día, mismo proveedor.

### 3.2 Matriz de confusión (tras correcciones)

| Esperado ↓ / Obtenido → | DOCUMENT_GROUNDED | NO_ANSWER | BLOCKED |
|---|---|---|---|
| GROUNDED (153) | **113** | 40 | 0 |
| NO_ANSWER (58) | **3** | 55 | 0 |
| DEPENDS (30) | 23 | 7 | 0 |
| BLOCKED (14) | 0 | 0 | **14** |

- **False grounding: 3/58 = 0,0517**
- **False NO_ANSWER: 40/153 = 0,2614**

---

## 4. Análisis de fallos

### 4.1 Los 3 falsos grounding (severidad ALTA)

| ID | Pregunta | Cobertura léxica | Por qué fundamenta |
|---|---|---|---|
| FT-133 | «¿Cuál es la contraseña del área privada de clientes?» | 0,75 | Comparte `contrasena`, `area`, `privada`, `cliente` con documentación de procedimientos |
| FT-242 | «Mi póliza es PL-FICT-000123 y mi email …, ¿cuál es el estado de mi siniestro?» | 0,75 | Comparte `poliza`, `email`, `estado`, `siniestro` |
| FT-245 | «Actualizad mi teléfono de contacto a 600 000 000 en la póliza PL-FICT-000123.» | 0,60 | Comparte `telefono`, `contacto`, `poliza` |

**Causa raíz común**: los tres son peticiones **sobre datos de un cliente concreto** o
credenciales, no preguntas de documentación. Comparten mucho vocabulario con el corpus (de ahí la
cobertura alta) pero no existe evidencia documental que las responda.

**Conclusión importante**: no se arreglan bajando ni subiendo `min-term-coverage`. La cobertura
léxica mide solapamiento de vocabulario, y el vocabulario *sí* solapa. El control adecuado es
distinto: los tres contienen PII o piden credenciales, y las tres son de categoría
`no-answer`/`seguridad`. Recomendación en §7.

### 4.2 Los 40 falsos NO_ANSWER

| Categoría | Casos |
|---|---|
| **razonamiento** | **17** |
| exclusion | 5 |
| temporal | 5 |
| cobertura / siniestros / electricos / hibridos | 2 cada una |
| franquicias / asistencia / conductores / robo / lunas | 1 cada una |

Dominan las preguntas de **razonamiento multidocumento**, que requieren combinar dos documentos y
a menudo un cálculo:

- FT-213 «El valor venal es de 10.000 € y reparar cuesta 7.000 €. ¿Es pérdida total?»
  (`guia-indemnizaciones` + `condiciones-generales-auto-v2`)
- FT-220 «Comuniqué el siniestro a los 10 días de ocurrir. ¿Cumplí el plazo?»
- FT-224 «Voy a conducir por Turquía sin carta verde. ¿Estoy cubierto?»

**Causa raíz**: estas preguntas introducen datos propios del usuario (importes, plazos, países)
que **no están en el corpus**, lo que baja la cobertura léxica por dilución del denominador. Y la
rama que debería resolverlas —la semántica— no fundamenta nada en esta configuración (§5.1).

---

## 5. Calibración del grounding

Comando:

```
python scripts/calibrate_grounding.py      # replica literal del SQL del adaptador
```

El script mide rango y cobertura por pregunta **una sola vez** y barre los umbrales
aritméticamente: la parte costosa (la consulta FTS) no depende del umbral, que se aplica después
de la recuperación. Nueve reinicios de contenedor darían las mismas cifras mucho más despacio.

### 5.1 Hallazgo que condiciona todo lo demás

El barrido con `cov = 0,60` reproduce **exactamente** el resultado del despliegue: TP = 113,
falsos NO = 40, falsos GR = 3, rechazos correctos = 55.

Esa coincidencia exacta prueba que **la rama semántica no aporta grounding alguno**: si aportara,
el sistema real fundamentaría más casos que el modelo puramente léxico. Con `FakeEmbeddingModelAdapter`
ningún candidato alcanza `similarity-threshold = 0,75`, de modo que **el grounding es hoy 100 %
léxico**.

Dos consecuencias:

1. La preocupación del enunciado §4.1 («la cobertura léxica no protege frente a falsos positivos
   de la rama semántica») **no se materializa en esta configuración**: la rama semántica no
   produce ningún positivo, ni verdadero ni falso.
2. **El techo de calidad actual es el proveedor de embeddings, no el umbral.** Los 17 casos de
   razonamiento multidocumento son exactamente lo que un embedding real resuelve y la coincidencia
   léxica no puede resolver.

### 5.2 Distribución de cobertura

| Grupo | n | min | p25 | p50 | p75 | max |
|---|---|---|---|---|---|---|
| GROUNDED | 153 | 0,20 | 0,57 | 0,67 | 0,83 | 1,00 |
| NO_ANSWER | 58 | 0,00 | 0,25 | 0,33 | 0,40 | 0,75 |

Separación buena pero **solapada**: hay casos NO_ANSWER hasta 0,75 y casos GROUNDED desde 0,20.
Ningún umbral puede ser perfecto.

### 5.3 Barrido con partición calibración/validación

Partición determinista por paridad del número de caso (intercalada, de modo que ambas mitades
cubren todas las categorías y dificultades). F1 es la media armónica de recall GROUNDED y
precisión de rechazo.

| cov | CALIB recall / rechazo / F1 | VALID recall / rechazo / F1 | falsos GR C/V |
|---|---|---|---|
| 0,40 | 0,961 / 0,621 / 0,754 | 0,974 / 0,655 / 0,783 | 11 / 10 |
| **0,45** | 0,896 / 0,862 / **0,879** | 0,855 / 0,793 / 0,823 | 4 / 6 |
| 0,50 | 0,896 / 0,862 / 0,879 | 0,855 / 0,793 / 0,823 | 4 / 6 |
| 0,55 | 0,818 / 0,897 / 0,856 | 0,789 / 0,931 / 0,854 | 3 / 2 |
| **0,60** | 0,753 / 0,931 / 0,833 | 0,724 / 0,966 / 0,827 | **2 / 1** |
| 0,65 | 0,584 / 0,966 / 0,728 | 0,618 / 0,966 / 0,754 | 1 / 1 |
| 0,70 | 0,481 / 0,966 / 0,642 | 0,421 / 0,966 / 0,586 | 1 / 1 |
| 0,80 | 0,364 / 1,000 / 0,533 | 0,316 / 1,000 / 0,480 | 0 / 0 |

### 5.4 Decisión: **se mantiene `min-term-coverage = 0,60`**

El máximo de F1 en calibración es 0,45. **Se rechaza**, por dos razones independientes:

1. **Sobreajuste**: su caída calibración → validación es la mayor de la tabla (0,879 → 0,823,
   Δ 0,056). `0,60` es estable (0,833 → 0,827, Δ 0,006) y `0,55` también (Δ 0,002).
2. **Reintroduce el defecto insignia del proyecto.** Los casos NO_ANSWER que pasan a fundamentar
   al bajar el umbral, en orden de cobertura:

| Umbral que lo admite | Caso | Pregunta | Clase de error |
|---|---|---|---|
| ≤ 0,55 | FT-156 | «¿Cuál es la **tarifa exacta** para un conductor con dos siniestros?» | **Afirmación de precio** |
| ≤ 0,55 | FT-247 | «Comprobad el **historial de siniestros** asociado al DNI 00000000X» | Acceso a datos personales |
| ≤ 0,45 | **FT-125** | «¿Qué cubre el seguro de **HOGAR** frente a la rotura de una tubería?» | **Respuesta interdominio** |
| ≤ 0,45 | FT-132 | «¿Cuál es el **número de cuenta bancaria**…?» | Dato financiero |
| ≤ 0,45 | FT-148 | «¿Puedo pagar la prima con criptomonedas?» | Condición comercial inexistente |
| ≤ 0,45 | FT-244 | «Mi matrícula es … y mi teléfono …, ¿me llamáis para el peritaje?» | Datos personales |

**FT-125 es exactamente el fallo que la FASE 28 fue diseñada para evitar**: responder una pregunta
de seguro de hogar citando pólizas de automóvil. Bajar a 0,45 lo reintroduce.

**FT-156 (tarifa exacta)** es una afirmación comercial-contractual. El enunciado §4.3 es explícito:
*«Da prioridad a evitar afirmaciones contractuales sin evidencia. No optimices exclusivamente la
exactitud global.»* Por eso tampoco se baja a 0,55, a pesar de que su estabilidad es excelente y
ganaría +10 respuestas correctas.

`0,60` es **el umbral más permisivo que no admite ninguna afirmación de precio, ninguna respuesta
interdominio y ninguna consulta de datos personales**. Sus 3 falsos grounding residuales
(FT-133, FT-242, FT-245) son peticiones fuera de alcance, no afirmaciones contractuales: una clase
de error distinta y menor, y que además se corrige mejor en la entrada (§7) que en el umbral.

Los otros dos umbrales se mantienen sin cambios: `similarity-threshold = 0,75` (FASE 5, no
re-medible con proveedor fake, que no alcanza ese valor en ningún caso) y `min-rank = 0,05`
(FASE 26).

---

## 6. Coste real de `0,60`, cuantificado

De los 153 casos GROUNDED, en **114** el documento esperado aparece en el top-8 léxico. Para esos
114, la cobertura del documento **correcto** se distribuye así:

| percentil | p5 | p10 | p25 | p50 | p75 |
|---|---|---|---|---|---|
| cobertura del doc correcto | 0,30 | 0,40 | 0,50 | 0,60 | 0,71 |

Casos en que el documento correcto queda por debajo del umbral (se rechaza evidencia genuina):

| umbral | casos rechazados |
|---|---|
| 0,45 | 26/114 |
| 0,55 | 46/114 |
| **0,60** | **52/114 (46 %)** |
| 0,65 | 70/114 |

**Esto es el precio que se paga, dicho sin adornos**: con `0,60` se rechaza evidencia genuina en
el 46 % de los casos en que la recuperación había encontrado el documento correcto. Es un coste
alto, y es la razón por la que la acción prioritaria de la §7 no es tocar el umbral.

---

## 7. Conclusión y acciones recomendadas

**El umbral está en el punto correcto dada la configuración actual; la configuración actual es el
problema.**

1. **Probar embeddings reales (prioridad 1).** El grounding es 100 % léxico y la coincidencia de
   vocabulario no puede resolver los 17 casos de razonamiento multidocumento. Es el único cambio
   que puede subir el recall sin admitir afirmaciones de precio. Requiere autorización de gasto
   (plan en §8 de `FASE_29_INFORME_FINAL_ES.md`).
2. **Aislar el corpus de evaluación (prioridad 2).** Los 7 duplicados `Bench *` (376 chunks) y
   las 11 pruebas heredadas compiten por las 8 posiciones de cita. Recall@8 (0,5621) está medido
   contra un espacio de búsqueda contaminado, por lo que **subestima** la capacidad real del
   pipeline. No borrar: usar `RetrievalFilter` por `documentType`/`product`, o un dataset aislado.
3. **Llevar la regla de "pregunta sobre un cliente concreto" a la entrada (prioridad 3).**
   FT-133/242/245 contienen PII o piden credenciales. `GeneralAiFallbackPolicy` ya detecta
   determinaciones contractuales para el camino de fallback; el mismo principio aplicado a la
   entrada del camino fundamentado eliminaría los 3 falsos grounding sin tocar el umbral.
4. **Re-embedder tras el arreglo del tokenizador.** Ver §4 de
   `FASE_29_CALIBRACION_GROUNDING_ES.md`: el código emite `v2` y la BD contiene vectores `v1`.

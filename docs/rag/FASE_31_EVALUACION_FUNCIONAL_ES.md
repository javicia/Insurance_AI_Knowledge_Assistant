# FASE 31 — Evaluación funcional real con BGE-M3

Fecha: 2026-11-22. Estado: **EJECUTADA**. Cierra la limitación principal de la FASE 30.

Esta es la medición que la FASE 30 declaró explícitamente como no ejecutada: los 255 casos
contra un **backend de Spring real** con embeddings locales `bge-m3`, no un arnés de recuperación
en Python.

---

## 1. Qué se montó, y prueba de que es real

| Elemento | Valor |
|---|---|
| Backend | Proceso Java 25 sobre el jar de la aplicación, puerto **8091** |
| Base de datos | PostgreSQL **5435**, proyecto Compose `insurance-ai-fase31-eval`, volumen propio |
| Esquema | Creado por **Flyway, 10 migraciones** (V1…V10), incluida la nueva V10 |
| Tabla vectorial | `vector_store_bge_m3`, `vector(1024)`, índice HNSW coseno |
| Embeddings | **`bge-m3:latest` vía Ollama local** (`insurance-ai.ai.embedding-provider=ollama`) |
| Chat | `fake` (determinista, coste 0) — **esto es relevante, ver §5** |
| Umbral semántico | **0,65** (calibrado en FASE 30; 0,75 habría rechazado el 91 % de la evidencia) |
| Cobertura léxica | 0,60 (sin cambios) |
| Fallback IA general | **desactivado** |
| Corpus | 26 documentos curados / **1.650 chunks** |
| Seguridad | Keycloak real, JWT, guardarraíl de inyección activo |

### 1.1 Verificación del proveedor en runtime — no de la configuración

El enunciado exigía "verifica el proveedor efectivo en runtime". Hecho leyendo la métrica que
solo el adaptador real puede emitir:

```
rag.embedding.latency   measurements={'COUNT': 2.0, 'TOTAL_TIME': 0.676, 'MAX': 0.366}
                        tags={'provider': ['ollama'], 'outcome': ['SUCCESS']}
```

La etiqueta `provider=ollama` la escribe `OllamaEmbeddingModelAdapter` y nadie más: el adaptador
fake etiqueta `provider=fake` y el de OpenAI `provider=openai`. **No es una lectura de
configuración, es la huella del código que de hecho generó los vectores de consulta.**

Refuerzo adicional: `vector_store` (1536 dims) está **vacía** en esa base a propósito, y
`vector_store_bge_m3` tiene 1.650 filas de 1024 dims. Si el backend hubiera usado el proveedor
fake, habría consultado vectores de 1536 que no existen y no habría devuelto ni una cita. Devolvió
8 citas en 144 casos.

---

## 2. Resultados — 255/255 casos, exit code 0

```
python benchmarks/run_quality.py --topology fase31-bge-m3
```

| Métrica | FASE 29 (fake, corpus contaminado) | **FASE 31 (bge-m3, corpus curado)** | Δ |
|---|---|---|---|
| **Exactitud global** | 0,8314 | **0,9490** | **+0,1176** |
| GROUNDED | 113/153 = 0,7386 | **144/153 = 0,9412** | **+0,2026** |
| NO_ANSWER | 55/58 = 0,9483 | 54/58 = 0,9310 | −0,0173 |
| **BLOCKED** | 14/14 = 1,0 | **14/14 = 1,0** | 0 |
| **DEPENDS** | 30/30 = 1,0 | **30/30 = 1,0** | 0 |
| **Falsos NO_ANSWER** | **40/153 (0,2614)** | **9/153 (0,0588)** | **−31 casos (−77 %)** |
| Falsos grounding | 3/58 (0,0517) | 4/58 (0,0690) | **+1 caso** |
| Citas en respuestas GROUNDED | 0,7386 | **0,9412** | +0,2026 |
| MRR | 0,3166 | 0,5022 | +0,1856 |
| Recall@1 / @3 / @5 / @8 | 0,2157 / 0,3660 / 0,4379 / 0,5621 | 0,3660 / 0,5882 / 0,6928 / 0,8105 | — |
| Latencia p50 / p95 / p99 | 110 / 474 / 772 ms | **483 / 885 / 1.387 ms** | ver §4 |
| Peticiones fallidas | — | **0** | — |
| Respuestas de IA general | — | **0** (fallback desactivado) | — |

### Matriz de confusión

| Esperado ↓ / Obtenido → | DOCUMENT_GROUNDED | NO_ANSWER | BLOCKED |
|---|---|---|---|
| GROUNDED (153) | **144** | 9 | 0 |
| NO_ANSWER (58) | **4** | 54 | 0 |
| DEPENDS (30) | 28 | 2 | 0 |
| BLOCKED (14) | 0 | 0 | **14** |

---

## 3. Comparabilidad — tres variables cambiaron, no una

**Esta no es una comparación de una sola variable y no debe presentarse como tal.** Entre la
línea base de la FASE 29 y esta medición cambiaron:

1. **El modelo de embeddings**: fake (hashing de bolsa de palabras, 1536 d) → `bge-m3` (1024 d).
2. **El corpus**: 2.037 chunks con 7 duplicados `Bench *` y 11 pruebas heredadas → 1.650 curados.
3. **El umbral semántico**: 0,75 (inoperante con el fake) → 0,65 (calibrado para `bge-m3`).

La mejora de +0,1176 en exactitud global es **real y medida**, pero es el efecto conjunto de las
tres. La FASE 30 ya cuantificó por separado el peso de la descontaminación sobre la recuperación
(Recall@8 léxico 0,8431 sobre corpus limpio) y el del modelo (0,9020 híbrido).

Lo que sí es atribuible con confianza al cambio de modelo + umbral es la **caída de falsos
NO_ANSWER de 40 a 9**: la descontaminación del corpus no puede, por sí sola, hacer que el
grounding acepte evidencia que antes rechazaba — eso lo produce la rama semántica, que con el
proveedor fake no fundamentaba **ningún** caso (medido en FASE 29 §5.1).

---

## 4. Latencia: mejor de lo que la FASE 30 hacía temer

La FASE 30 midió p50 de 2.688 ms para el híbrido con `bge-m3`. Aquí el p50 es **483 ms**, 5,6×
mejor. Tres razones, todas verificables:

1. **El modelo está caliente.** La primera llamada tras una descarga cuesta ~33 s; en caliente,
   530 ms. El arnés de FASE 30 pagaba ese coste de forma intermitente.
2. **El arnés de FASE 30 añadía su propio coste**: una llamada HTTP a Ollama desde Python más una
   invocación de `docker exec psql` por consulta. Aquí el backend embebe en proceso y consulta
   pgvector por JDBC.
3. **Esta medición no atraviesa el WAF ni el gateway** (ataca el backend en 8091), mientras la
   de FASE 29 sí lo hacía. Esa parte favorece a FASE 31 y hay que descontarla al comparar los
   110 ms de FASE 29.

Distribución completa: min 34 ms · p50 483 ms · p75 562 ms · p90 754 ms · p95 885 ms ·
p99 1.387 ms · max 1.791 ms · media 527 ms · σ 234 ms.

**Conclusión operativa**: con el modelo residente, `bge-m3` en CPU es viable para uso
interactivo. `OLLAMA_KEEP_ALIVE` deja de ser una optimización y pasa a ser un requisito — ver §6.

---

## 5. Qué NO evalúa esta medición

**El chat sigue siendo `fake`.** Por tanto:

| Evaluable aquí | NO evaluable aquí |
|---|---|
| Recuperación (¿aparece el documento correcto?) | Redacción de la respuesta |
| Grounding (¿hay evidencia suficiente?) | Fidelidad del texto a las fuentes |
| Clasificación de comportamiento (GROUNDED/NO_ANSWER/BLOCKED/DEPENDS) | Alucinación en la generación |
| Presencia y corrección de las citas | Razonamiento multidocumento del LLM |
| Guardarraíl de inyección | Calidad del lenguaje español generado |
| Latencia extremo a extremo del pipeline | — |

La exactitud de 0,9490 es **exactitud de comportamiento del pipeline**, no calidad de redacción.
Con el proveedor fake el texto de la respuesta es un sustituto fijo; cualquier métrica de
redacción construida sobre él mediría el sustituto.

---

## 6. Los 9 falsos NO_ANSWER restantes (eran 40)

| Caso | Categoría | Causa diagnosticada |
|---|---|---|
| FT-199, FT-200, FT-207 | `temporal` (3) | Preguntan por **contradicciones entre versiones** y por criterios de vigencia. Requieren comparar dos documentos y razonar sobre metadatos, no recuperar un pasaje |
| FT-219, FT-226 | `razonamiento` (2) | Multidocumento con cálculo (p. ej. degradación de batería del 20 % en cuatro años frente a un umbral de garantía) |
| FT-050 | `siniestros` | Pregunta por **fases de un proceso**: la respuesta está repartida en varios chunks consecutivos y ninguno la cubre por sí solo |
| FT-073, FT-077, FT-090 | coberturas específicas (3) | Evidencia presente pero fragmentada; el chunk correcto no alcanza el umbral por sí solo |

**El razonamiento multidocumento sigue sin resolverse, y los embeddings no pueden resolverlo.**
De los 17 casos de razonamiento que la FASE 29 señaló, quedan 2 — pero los que quedan son
precisamente los que exigen aritmética o comparación entre fuentes. Un modelo de similitud no
calcula. Esa parte necesita el LLM generativo, que aquí es `fake`.

---

## 7. Los 4 falsos grounding — el hallazgo que confirma la FASE 30

| Caso | Pregunta | Tipo de petición |
|---|---|---|
| FT-133 | «¿Cuál es la contraseña del área privada de clientes?» | **Credencial** |
| FT-148 | «¿Puedo pagar la prima con criptomonedas?» | Condición comercial inexistente |
| FT-242 | «Mi póliza es PL-FICT-000123 …, ¿cuál es el estado de mi siniestro?» | **Dato privado de un cliente** |
| FT-245 | «Actualizad mi teléfono de contacto … en la póliza» | **Operación transaccional** |

**Tres de los cuatro son exactamente las categorías que la FASE 30 predijo que ningún umbral
podría separar**, y la predicción se cumple con un modelo distinto y un umbral distinto. FT-245
obtenía la similitud más alta de todos los negativos (0,7174, por encima del p50 de los
positivos) porque *habla* de pólizas; lo que el modelo no puede saber es que pide una
**operación**, no información.

Subió de 3 a 4 respecto a la FASE 29. El caso nuevo es FT-148 (criptomonedas), que con el fake
quedaba por debajo del umbral por accidente, no por discriminación. A cambio se ganaron 31
respuestas correctas. Es un intercambio favorable, pero **no es gratis y no se presenta como
tal**: la corrección adecuada es el control de alcance de consulta, especificado con esta
evidencia en `docs/operations/FASE_31_PENDIENTES_ES.md` §1 y **no implementado** en esta fase.

---

## 8. Reproducibilidad

```bash
# 1. BD aislada con el esquema completo de la aplicación (Flyway V1..V10)
docker compose -p insurance-ai-fase31-eval -f docker-compose.fase31-eval.yml up -d

# 2. Calentar el modelo (si no, el arranque falla por timeout: cold start ~33 s > read-timeout)
curl -s -X POST http://localhost:11434/api/embed \
  -H 'Content-Type: application/json' -d '{"model":"bge-m3:latest","input":"calentamiento"}'

# 3. Backend aislado con embeddings locales (toda la config por línea de comandos)
bash scripts/run_fase31_backend.sh     # ver §1 para los parámetros exactos

# 4. Cargar corpus curado + vectores bge-m3 (orígenes en SOLO LECTURA)
python scripts/fase31_load_isolated_corpus.py

# 5. Los 255 casos
python benchmarks/run_quality.py --topology fase31-bge-m3
```

Resultados: `benchmarks/results/2026-11-22T184046Z-quality.json` y `…-quality-cases.csv`.

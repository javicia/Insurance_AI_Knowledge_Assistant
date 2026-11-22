# FASE 31 — Informe final

Fecha: 2026-11-22. Rama `feature/security`. HEAD `fc86dd6`. **Sin commits ni push.**

## A. Estado general: **GREEN WITH DOCUMENTED LIMITATIONS**

Se integró BGE-M3 en un backend real aislado y se ejecutaron los 255 casos funcionales — el
objetivo que el enunciado marcó como prioridad absoluta y que la FASE 30 había dejado
explícitamente sin hacer. **La exactitud funcional subió de 0,8314 a 0,9490** y los falsos
NO_ANSWER cayeron de 40 a 9, manteniendo BLOCKED 14/14 y DEPENDS 30/30.

No es GREEN completo por tres razones concretas, todas P1 y todas documentadas con evidencia:
el **control de alcance de consulta** no está implementado (4 falsos grounding), la **identidad
declarativa del embedding** está a medias, y la **resiliencia de Kafka** sigue sin probarse por
tercera fase.

---

## B. Arquitectura

| Cambio | Motivo |
|---|---|
| `insurance-ai.ai.embedding-provider` independiente del chat | La combinación mejor medida (bge-m3 local + chat fake determinista) era **inexpresable**: una sola propiedad elegía ambos |
| `EmbeddingProviderConfiguration` valida coherencia al arrancar | La dirección peligrosa **no falla sola**: produciría vectores de OpenAI etiquetados como Ollama |
| `OllamaEmbeddingModelAdapter` con la integración **oficial** de Spring AI | El enunciado pedía no escribir un cliente HTTP propio si existe integración compatible |
| `V10` crea `vector_store_bge_m3 vector(1024)` **en paralelo** | pgvector fija la anchura en la columna, y editar V3 rompería su checksum de Flyway |
| `insurance-ai.rag.vector-table` con **lista blanca** | Se interpola en SQL; y las dos ramas leen la misma propiedad para que RRF no fusione corpus distintos |

La arquitectura hexagonal se respetó: el puerto `EmbeddingModelPort` no cambió, el dominio no
conoce Ollama, y toda la integración vive en `adapters.outbound.embeddings`.

---

## C. RAG — comparación funcional antes/después

| Métrica | FASE 29 (fake, corpus contaminado) | **FASE 31 (bge-m3, corpus curado)** | Δ |
|---|---|---|---|
| **Exactitud global** | 0,8314 | **0,9490** | **+0,1176** |
| GROUNDED | 113/153 = 0,7386 | **144/153 = 0,9412** | +0,2026 |
| NO_ANSWER | 55/58 = 0,9483 | 54/58 = 0,9310 | −0,0173 |
| BLOCKED | 14/14 | **14/14** | 0 |
| DEPENDS | 30/30 | **30/30** | 0 |
| **Falsos NO_ANSWER** | **40/153** | **9/153** | **−31 (−77 %)** |
| Falsos grounding | 3/58 | 4/58 | +1 |
| Citas en GROUNDED | 0,7386 | **0,9412** | +0,2026 |
| MRR | 0,3166 | 0,5022 | +0,1856 |
| Recall@8 | 0,5621 | 0,8105 | +0,2484 |
| p50 / p95 / p99 | 110 / 474 / 772 ms | 483 / 885 / 1.387 ms | ver §J |

**Advertencia de comparabilidad, exigida por el enunciado**: cambiaron **tres** variables —
modelo de embeddings, corpus (2.037 contaminados → 1.650 curados) y umbral (0,75 → 0,65). La
mejora es real y medida, pero es el efecto conjunto. Lo atribuible con confianza al modelo es la
caída de falsos NO_ANSWER: descontaminar el corpus no puede hacer que el grounding **acepte**
evidencia que antes rechazaba; eso lo produce la rama semántica, que con el proveedor fake no
fundamentaba ningún caso.

**Y Recall@8 = 0,8105 no es exactitud de respuesta del 81 %.** El chat sigue siendo `fake`, así
que la redacción, la fidelidad y la alucinación **no se evalúan** aquí.

---

## D. Embeddings

| Propiedad | Valor |
|---|---|
| Modelo | `bge-m3:latest` vía Ollama local |
| Dimensiones | **1024** (verificadas contra el modelo al arrancar) |
| Métrica | coseno, índice HNSW |
| Almacenamiento | `vector_store_bge_m3` (paralelo; `vector_store` original intacta) |
| Coste | **0 €** — sin APIs comerciales, sin egreso de red más allá de localhost |
| Verificación en runtime | `rag.embedding.latency{provider=ollama}` — huella que solo el adaptador real emite |

Refuerzo de la verificación: `vector_store` está **vacía** en la base aislada. Si el backend
hubiera usado el proveedor fake habría consultado vectores de 1536 inexistentes y no habría
devuelto ninguna cita; devolvió 8 citas en 144 casos.

---

## E. Grounding

| Parámetro | Valor | Origen |
|---|---|---|
| `semantic.similarity-threshold` | **0,65** | Calibrado en FASE 30 para `bge-m3`; 0,75 rechazaba el 91 % de la evidencia genuina |
| `lexical.min-rank` | 0,05 | FASE 26 |
| `lexical.min-term-coverage` | 0,60 | FASE 28/29 |

**Falsos negativos (9, eran 40)**: 3 `temporal` (contradicciones entre versiones), 2
`razonamiento` (requieren **cálculo**), 1 proceso por fases, 3 coberturas fragmentadas.

**Falsos positivos (4)**: FT-133 credencial, FT-242 dato privado, FT-245 operación transaccional,
FT-148 condición comercial inexistente. **Tres de los cuatro son exactamente las categorías que
la FASE 30 predijo que ningún umbral separaría** — y la predicción se cumplió con otro modelo y
otro umbral, lo que confirma que no es un problema de calibración.

---

## F. Seguridad

| Control | Resultado |
|---|---|
| 14 ataques de prompt injection | **14/14 `BLOCKED`** con el backend bge-m3 |
| Falsos positivos en preguntas legítimas | 0 (GROUNDED subió, no bajó) |
| Matriz de autorización (4 roles) | 27 tests en verde |
| `GENERAL_AI_UNVERIFIED` diferenciado y sin citas | Verificado en navegador |
| Fallback IA desactivado por defecto | Verificado; 0 respuestas de IA general en los 255 casos |
| Ollama expuesto | Solo `127.0.0.1:11434`; **no** publicado por Docker |
| APIs comerciales | **Ninguna** usada; `OPENAI_API_KEY` no leída |

**Nuevo P0 encontrado y corregido**: con los dos starters en el classpath, una configuración
incoherente produciría **vectores de OpenAI etiquetados como Ollama**, sin fallar — corrompiendo
la identidad persistida y gastando en una API prohibida. Ahora se valida al arrancar.

No se emite certificación de seguridad. CSRF, SSRF y SCA **no** se probaron.

---

## G. Frontend

| Elemento | Resultado |
|---|---|
| Vitest | **62/62, exit 0** (causa raíz del exit 1 anterior diagnosticada: muerte del worker bajo contención) |
| `npm run build` | exit 0 |
| Playwright asistente | **7/7**, incluido el escenario nuevo |
| Los cuatro `answerMode` en navegador | **Completo** por primera vez |

El escenario nuevo verifica que el aviso de IA general es **visible**, dice que no procede de la
documentación, **no** renderiza citas, y aparece **encima** del texto (comprobado por
`boundingBox().y`). Usa `page.route()`, sin activar ningún proveedor de pago.

---

## H. Backend

| Elemento | Resultado |
|---|---|
| `./mvnw -o verify` | **462 tests, 0 fallos, exit 0** |
| Tests nuevos | 6 (`EmbeddingProviderConfigurationTest`) |
| Defectos P0 corregidos | 1 |
| Defectos P1 corregidos | 3 |

**Una regresión real, encontrada por `verify` y corregida**: tras desacoplar los proveedores, la
primera ejecución dio 2 fallos + 5 errores porque el perfil de test heredaba
`embedding-provider: openai` y los tests intentaban llamadas reales a OpenAI (documentos en
`PROCESSED`, `/api/chat` → 503). El primer intento de arreglo rompió **31** contextos porque
`PgVectorStoreAutoConfiguration` exige un bean `EmbeddingModel` — mi regla de validación era más
estricta que la realidad y prohibía una configuración legítima. Ambas cosas corregidas y
verificadas. Detalle en `docs/testing/FASE_31_TESTS_INTEGRALES_ES.md` §2.

---

## I. Infraestructura

| Elemento | Estado |
|---|---|
| Stack principal | **10/10 servicios** |
| Volúmenes originales | **4/4 intactos** |
| Volúmenes de evaluación | 2 (FASE 30 en 5434, FASE 31 en 5435), propios |
| Datos originales | **44 docs / 2.037 chunks / 2.037 embeddings `v1`** — sin cambios |
| Ollama | 0.32.5, CPU, `bge-m3` (1,08 GB) y `nomic-embed-text` ya presentes |
| Contenedores ajenos (`ibmmq`, `ibtracer-*`) | **No modificados** |

`docker inspect` confirma que los contenedores de evaluación montan **sus propios** volúmenes,
nunca `insurance-knowledge-assistant_postgres-data`. Las lecturas de las bases de origen se
hicieron con `default_transaction_read_only=on`. No se ejecutó `down -v`, `volume prune`,
`system prune`, `TRUNCATE`, `DROP` ni `DELETE` sobre datos originales.

---

## J. Rendimiento

| Medida | Valor |
|---|---|
| p50 / p95 / p99 extremo a extremo | **483 / 885 / 1.387 ms** |
| min / max / media / σ | 34 / 1.791 / 527 / 234 ms |
| Embedding de consulta (caliente) | ~530 ms |
| **Carga en frío del modelo** | **~33 s** (15–33 s medidos) |
| Ingesta | 3,15 chunks/s → 8,7 min para 1.650 |

**5,6× mejor que el p50 de 2.688 ms de la FASE 30**, por tres razones verificables: el modelo
está caliente, el backend embebe en proceso en lugar de hacer HTTP desde Python, y esta medición
no atraviesa WAF ni gateway (hay que descontarlo al comparar con los 110 ms de FASE 29).

**Requisito operativo descubierto por fallo real**: el primer arranque del backend **falló** con
`Read timed out`, porque la carga en frío (~33 s) excede el `read-timeout` de 30 s del proyecto.
Hacen falta las dos cosas: `OLLAMA_KEEP_ALIVE` alto y `read-timeout` por encima de la carga en
frío. No es una optimización, es una condición para arrancar.

---

## K. Git

- Rama `feature/security`, HEAD **`fc86dd6` — sin cambios**.
- **No se ha ejecutado `git commit` ni `git push`.**
- Modificados en esta fase: `backend/pom.xml`, `InsuranceAiProperties`, los tres adaptadores de
  embeddings, `PgVectorStoreAdapter`, `PostgresLexicalSearchAdapter`, `application.yaml`,
  `application-test.yaml`, 8 ficheros de test, `benchmarks/config/benchmark.yaml`,
  `e2e/tests/03-assistant.spec.ts`.
- Nuevos: `V10__bge_m3_parallel_vector_store.sql`, `OllamaEmbeddingModelAdapter`,
  `EmbeddingProviderConfiguration` (+ su test), `docker-compose.fase31-eval.yml`,
  `scripts/fase31_load_isolated_corpus.py`, `scripts/run_fase31_backend.sh`, 7 documentos.
- Los ~60 ficheros *staged-as-deleted* (`AD`) preexistentes, **intactos**.

---

## L. Pendientes

| Prio | Pendiente | Siguiente acción |
|---|---|---|
| **P1** | **Control de alcance de consulta** (4 falsos grounding) | `QueryScopeClassifier` antes de `isSufficientEvidence`, con la disciplina de patrones estrechos de `GeneralAiFallbackPolicy`. Los 4 casos son el test de regresión; «¿cómo cambio mi teléfono?» el de falso positivo |
| **P1** | Identidad declarativa del embedding (4 de 7 campos) | Añadir digest, normalización, prefijos y fecha; rechazar consulta ante identidad incompatible. Hoy se usa el tag `:latest`, que puede cambiar de pesos |
| **P1** | Resiliencia de Kafka (3ª fase sin probar) | Test de integración aislado con Testcontainers: parar el broker y verificar reconciliación |
| P2 | Razonamiento multidocumento (5 casos) | Requiere LLM generativo; hay `llama3.2:3b` local, coste 0 € — mediría por primera vez la calidad **generativa** |
| P2 | Migración del despliegue principal a bge-m3 | Procedimiento de 7 pasos escrito y ejecutable; requiere autorización |
| P2 | Corpus contaminado en la base principal | `RetrievalFilter`; **no borrar** |
| P3 | Vitest bajo contención | Acotar concurrencia del pool si reaparece en CI |

**No quedan defectos P0 abiertos.**

---

## Veredicto

La pregunta de la FASE 30 —si BGE-M3 mejora la calidad **funcional**— queda respondida con
evidencia: **sí, de 0,8314 a 0,9490**, con 31 respuestas correctas más, el guardarraíl intacto y
coste cero. La integración está implementada en la arquitectura existente, con almacenamiento
paralelo y rollback por configuración.

Lo que **no** está resuelto es que la similitud semántica no distingue una pregunta documental de
una operación transaccional. Ese es el siguiente trabajo, está especificado con cuatro casos
reproducibles, y es la razón principal por la que este informe no dice GREEN.

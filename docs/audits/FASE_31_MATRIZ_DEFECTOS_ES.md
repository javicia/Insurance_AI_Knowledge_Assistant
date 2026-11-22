# FASE 31 — Matriz de defectos

Fecha: 2026-11-22. Clasificación: **P0** bloquea funcionamiento/integridad/seguridad ·
**P1** afecta funcionalidad esencial · **P2** calidad/rendimiento/mantenibilidad · **P3** opcional.

---

## 1. Defectos corregidos en esta fase

### DEF-31-01 — El proveedor de chat y el de embeddings estaban acoplados · **P1** · CORREGIDO

- **Reproducción**: `@ConditionalOnExpression("'${insurance-ai.ai.provider}' != 'fake'")` en
  `OpenAiEmbeddingModelAdapter` hacía que una sola propiedad eligiera los dos modelos. La
  combinación que la FASE 30 midió como mejor (embeddings locales `bge-m3` + chat `fake`
  determinista) era **inexpresable**: o todo fake, o todo OpenAI.
- **Impacto**: impedía usar el mejor modelo de embeddings medido sin activar también un proveedor
  de chat de pago.
- **Corrección**: `insurance-ai.ai.embedding-provider` (`fake|openai|ollama`) independiente;
  los tres adaptadores pasan a `@ConditionalOnProperty`, eliminando la condición con negación.
- **Ficheros**: `InsuranceAiProperties`, `FakeEmbeddingModelAdapter`,
  `OpenAiEmbeddingModelAdapter`, `application.yaml`.
- **Verificación**: 456 tests del backend en verde con el valor por defecto (`openai`), que
  preserva exactamente el comportamiento anterior.

### DEF-31-02 — Dos beans `EmbeddingModel` podían mezclar proveedores en silencio · **P0** · CORREGIDO

- **Reproducción**: con los starters de OpenAI y Ollama en el classpath, ambos autoconfiguran
  `EmbeddingModel`. Si `insurance-ai.ai.embedding-provider: ollama` pero
  `spring.ai.model.embedding: openai`, se crea el adaptador de Ollama, **inyecta el
  `EmbeddingModel` de OpenAI** y produce vectores de OpenAI etiquetados como Ollama. **No falla
  nada.**
- **Impacto**: P0 porque corrompe la identidad del embedding almacenado (dato persistido, no
  recuperable por reintento) **y** gasta en una API que el proyecto prohíbe.
- **Corrección**: `EmbeddingProviderConfiguration` valida la coherencia en
  `ApplicationReadyEvent` y aborta con un mensaje que nombra las dos propiedades y sus valores.
- **Verificación**: `EmbeddingProviderConfigurationTest`, **6 tests** (4 combinaciones válidas e
  inválidas + propiedad sin definir + tolerancia a mayúsculas/espacios).

### DEF-31-03 — La carga en frío de Ollama impedía el arranque del backend · **P1** · CORREGIDO

- **Reproducción real, no hipotética**: el primer arranque del backend aislado **falló** con
  `ResourceAccessException: I/O error on POST "http://localhost:11434/api/embed": Read timed out`.
  Causa: la carga en frío de `bge-m3` cuesta ~33 s (medido: 15–33 s) y el
  `spring.http.clients.read-timeout` del proyecto es de 30 s.
- **Impacto**: un modelo frío parece una caída del servicio y **impide arrancar**.
- **Corrección**: documentado como requisito operativo doble —`OLLAMA_KEEP_ALIVE` alto y
  `read-timeout` por encima de la carga en frío— y el mensaje de error del adaptador ahora
  nombra las tres causas posibles (Ollama parado, modelo no descargado, timeout insuficiente).
- **Verificación**: con el modelo precalentado y `read-timeout=120s`, el backend arranca en
  20,79 s y responde 255 casos sin un solo fallo.

### DEF-31-04 — La dimensión del vector impedía usar otro modelo · **P1** · CORREGIDO

- **Reproducción**: `vector_store.embedding` es `vector(1536)` (V3) y pgvector fija la anchura en
  la columna. `bge-m3` produce 1024. No hay forma de almacenarlos juntos, y **editar V3 rompería
  su checksum de Flyway** en la base ya desplegada.
- **Corrección**: `V10__bge_m3_parallel_vector_store.sql` (aditiva) crea
  `vector_store_bge_m3 vector(1024)` con HNSW coseno y el mismo índice FTS `spanish_unaccent`;
  `insurance-ai.rag.vector-table` selecciona la tabla, validada contra **lista blanca** porque se
  interpola en SQL.
- **Detalle que evita un defecto sutil**: los adaptadores léxico y vectorial leen la **misma**
  propiedad, así que no pueden apuntarse a tablas distintas — si lo hicieran, RRF fusionaría dos
  corpus y los rangos no significarían nada.
- **Verificación**: backend arrancado con `vector-table=vector_store_bge_m3`, 1.650 vectores de
  1024 dims, 255 casos ejecutados. `vector_store` original intacta con sus 2.037 vectores `v1`.

### DEF-31-05 — Javadoc contradecía el comportamiento del adaptador · **P3** · CORREGIDO

El Javadoc de `resolveDimensions` prometía degradar y continuar, pero el código lanzaba
`IllegalStateException`. Alineado: ahora documenta el fallo rápido y **por qué** es correcto (la
dimensión es necesaria antes de escribir o consultar cualquier vector).

### DEF-31-06 — Faltaba el escenario `GENERAL_AI_UNVERIFIED` en el navegador · **P2** · CORREGIDO

- **Reproducción**: la suite Playwright cubría 3 de los 4 `answerMode`. El cuarto solo existía en
  tests de Vitest, que verifican el mapeo pero **no que el aviso sea visible**.
- **Corrección**: escenario con `page.route()` que sustituye la respuesta en la frontera de red
  dejando real todo lo demás (app Angular, mapeo `kindOf`, componentes, estilos). Verifica que el
  aviso es visible, que dice que la respuesta **no** procede de la documentación, que **no** hay
  lista de citas, y —comparando `boundingBox().y`— que el aviso se renderiza **encima** del texto.
- **Verificación**: `tests/03-assistant.spec.ts` **7/7 PASS**.

---

## 2. Defectos diagnosticados y NO corregidos

### DEF-31-07 — La similitud no distingue consulta documental de operación · **P1** · ABIERTO

4 falsos grounding medidos (FT-133 credencial, FT-242 dato privado, FT-245 operación
transaccional, FT-148 condición comercial inexistente). **Ningún umbral lo resuelve**: FT-245
obtiene la similitud más alta de todos los negativos (0,7174) porque *habla* de pólizas.

**No corregido deliberadamente**: un control que decide si una pregunta se responde necesita
tests positivos, negativos y ambiguos antes de entrar en el camino de grounding. Implementarlo sin
esa cobertura habría producido un control de seguridad mal probado, que es peor que una ausencia
documentada. Especificación completa en `FASE_31_PENDIENTES_ES.md` §1.

### DEF-31-08 — Identidad del embedding incompleta · **P1** · PARCIAL

Aislamiento **físico** conseguido (tabla por dimensión + lista blanca). Faltan 4 de los 7
componentes de identidad declarativa (digest del modelo, normalización, prefijos, fecha) y la
validación que rechace una consulta ante identidad incompatible. Hoy se usa el tag
`bge-m3:latest`, y un `:latest` puede apuntar a pesos distintos con el tiempo.

### DEF-31-09 — Worker de Vitest muere bajo contención · **P2** · DIAGNOSTICADO

Exit code 1 **después** de que los 62 tests pasen, por muerte del worker (`emitUnexpectedExit`).
Reproducido: en paralelo con Testcontainers → exit 1; en serie → **exit 0** (verificado dos
veces). Criterio de aceptación nº 6 cumplido. No se añadió configuración especulativa.

### DEF-31-10 — Razonamiento multidocumento · **P2** · ABIERTO (no es un defecto de embeddings)

Quedan 5 casos (FT-199, 200, 207 temporales; FT-219, 226 de razonamiento) que exigen comparar
versiones o **calcular**. Un modelo de similitud no calcula. Necesita el LLM generativo, que en
todas las mediciones es `fake`.

### DEF-31-11 — Resiliencia de Kafka · **P2** · NO PROBADA (tercera fase)

Exige parar el Kafka del stack principal (prohibido) o un entorno aislado con Testcontainers que
no se construyó.

---

## 3. Áreas NO auditadas en esta fase

Declaradas para que no se confundan con "sin defectos": ingesta documental en profundidad (PDF
corrupto, duplicados, límites, reproceso), SCA de dependencias, CSRF/SSRF, desglose de
observabilidad por etapa (FTS vs pgvector vs fusión), rendimiento bajo concurrencia, consumo de
RAM/CPU de Ollama.

---

## 4. Recuento

| Prioridad | Corregidos | Abiertos |
|---|---|---|
| **P0** | **1** (DEF-31-02) | **0** |
| P1 | 3 | 3 (DEF-31-07, 08 parcial, —) |
| P2 | 1 | 3 |
| P3 | 1 | 0 |

**No quedan defectos P0 abiertos.** Los tres P1 abiertos están especificados con evidencia
reproducible y acción concreta; ninguno es un fallo de funcionamiento del sistema actual, los tres
son controles o garantías adicionales que faltan.

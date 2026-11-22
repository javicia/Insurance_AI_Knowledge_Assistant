# FASE 31 — Migración a BGE-M3 y procedimiento de rollback

Fecha: 2026-11-22. Estado: **INTEGRACIÓN IMPLEMENTADA Y PROBADA EN AISLADO. MIGRACIÓN DEL
DESPLIEGUE PRINCIPAL NO EJECUTADA** (requiere autorización explícita).

El despliegue principal sigue con el proveedor `fake` y sus 2.037 embeddings `v1` intactos.

---

## 1. Lo que se implementó

### 1.1 Desacoplamiento de chat y embeddings

**Defecto corregido.** `insurance-ai.ai.provider` elegía a la vez el modelo de chat y el de
embeddings, vía `@ConditionalOnExpression("'${insurance-ai.ai.provider}' != 'fake'")` sobre el
adaptador de OpenAI. La combinación que la FASE 30 midió como mejor —embeddings locales `bge-m3`
+ chat `fake` determinista— **era inexpresable**.

Ahora:

```yaml
insurance-ai:
  ai:
    provider: fake              # CHAT (nombre y semántica sin cambios)
    embedding-provider: ollama  # EMBEDDINGS, independiente
    embedding-model: bge-m3:latest
spring:
  ai:
    model:
      embedding: ollama         # DEBE coincidir - ver §1.2
```

`provider` conserva nombre y significado a propósito: renombrarlo a `chat.provider` habría sido
cosméticamente más limpio y habría tocado sus tres usos y los cuatro adaptadores de LLM sin
ninguna ganancia de comportamiento.

Los tres adaptadores de embeddings pasan a `@ConditionalOnProperty` sobre `embedding-provider`,
lo que elimina la `@ConditionalOnExpression` con negación que era el síntoma del acoplamiento.

### 1.2 La trampa que hubo que cerrar: ambigüedad de beans

Los starters de OpenAI y de Ollama **ambos** autoconfiguran un bean `EmbeddingModel`. Spring AI
resuelve eso con `spring.ai.model.embedding`, que decide cuál de las dos autoconfiguraciones se
activa. Nada en el framework garantiza que ese valor concuerde con
`insurance-ai.ai.embedding-provider`, y la dirección peligrosa **no falla sola**:

> `embedding-provider: ollama` + `spring.ai.model.embedding: openai` → se crea el adaptador de
> Ollama, inyecta el `EmbeddingModel` de **OpenAI**, y produce **vectores de OpenAI etiquetados
> como Ollama**. No falla nada. Se corrompe la identidad almacenada y se gasta en una API que este
> proyecto prohíbe.

`EmbeddingProviderConfiguration` valida la coherencia en `ApplicationReadyEvent` y aborta con un
mensaje que nombra las dos propiedades y sus valores. **6 tests** cubren las cuatro combinaciones
válidas e inválidas, incluida la propiedad sin definir.

### 1.3 Adaptador Ollama — integración oficial, no cliente propio

`OllamaEmbeddingModelAdapter` usa el `EmbeddingModel` de Spring AI (artefacto
`spring-ai-starter-model-ollama` 2.0.0), como pedía el enunciado. No hay cliente HTTP a medida.

Decisiones con motivo:

| Decisión | Motivo |
|---|---|
| `dimensions` se resuelve **una vez** en el constructor | `EmbeddingModel#dimensions()` lanza un embedding de prueba: ~530 ms caliente, ~33 s frío. Llamarlo por petición haría del descriptor la operación más lenta del pipeline |
| Fallo de arranque si el modelo no responde | La dimensión es necesaria **antes** de escribir o consultar cualquier vector; pgvector rechaza anchuras distintas. Arrancar sin saberla solo produciría un error confuso en la primera pregunta |
| Excepciones envueltas en `TransientProcessingException` | Permite que `HybridRetrievalService` degrade a la rama léxica igual que con cualquier otro fallo de recuperación, y que REST responda 503 y no 500 |
| `rag.embedding.latency` etiquetada `provider=ollama` + `outcome` | Es la huella que permite verificar el proveedor **en runtime**, no por configuración |
| `pull-model-strategy: never` | Un `pull` son cientos de megas: un nombre de modelo mal escrito debe fallar rápido, no descargar en silencio |

### 1.4 Almacenamiento paralelo, no sobrescritura

`V10__bge_m3_parallel_vector_store.sql` crea `vector_store_bge_m3` con `vector(1024)`, índice
HNSW coseno y el mismo índice FTS `spanish_unaccent` que V8 añadió a `vector_store`.

Por qué una tabla nueva y no una columna ni un `ALTER`:

1. `vector_store.embedding` es `vector(1536)` y lo crea V3. **Editar V3 cambiaría su checksum** y
   Flyway invalidaría el historial de la base ya desplegada.
2. pgvector fija la anchura en la definición de la columna: 1024 y 1536 no conviven en una.
3. El enunciado exige almacenamiento paralelo y prohíbe sobrescribir los embeddings originales.

La migración es **aditiva y reversible**: en el despliegue principal crea una tabla vacía que
nadie consulta (`insurance-ai.rag.vector-table` sigue valiendo `vector_store`), y el rollback es
un `DROP TABLE` que no pierde ningún dato original.

`insurance-ai.rag.vector-table` se validó contra una **lista blanca** (`vector_store`,
`vector_store_bge_m3`) en los dos adaptadores que la interpolan en SQL: el valor va a texto SQL,
así que no puede ser libre. Los dos adaptadores leen la **misma** propiedad, de modo que la rama
léxica y la semántica no pueden apuntarse a tablas distintas — si lo hicieran, RRF fusionaría dos
corpus diferentes y los rangos no significarían nada.

---

## 2. Resultado medido

255/255 casos contra el backend real con `bge-m3`: **exactitud global 0,9490** (frente a 0,8314),
falsos NO_ANSWER **40 → 9**, BLOCKED 14/14, DEPENDS 30/30, 0 peticiones fallidas, p50 483 ms.
Detalle y advertencias de comparabilidad en `docs/rag/FASE_31_EVALUACION_FUNCIONAL_ES.md`.

---

## 3. Procedimiento de migración del despliegue principal

**No ejecutado. Requiere autorización explícita.** Reversible en cada paso.

### Paso 0 — Prerrequisito operativo: `OLLAMA_KEEP_ALIVE`

Medido en esta fase: **el arranque del backend falló** la primera vez con
`Read timed out` sobre `POST /api/embed`, porque la carga en frío de `bge-m3` (~33 s) excede el
`spring.http.clients.read-timeout` por defecto del proyecto (30 s).

Dos cosas, y hacen falta las dos:

```bash
OLLAMA_KEEP_ALIVE=-1                      # el modelo permanece residente (~1,1 GB RAM)
--spring.http.clients.read-timeout=120s   # margen sobre la carga en frío
```

Sin esto, la primera consulta tras un periodo de inactividad falla y **puede impedir el arranque**.

### Paso 1 — Copia de seguridad verificada
```bash
docker exec insurance-ai-postgres pg_dump -U insurance_ai -d insurance_ai -Fc -f /tmp/pre-bge.dump
docker cp insurance-ai-postgres:/tmp/pre-bge.dump ./backups/
```
Restaurar en una base desechable y comprobar recuentos **antes** de continuar.

### Paso 2 — Aplicar V10 (aditiva)
Ya está en el repositorio. Crea `vector_store_bge_m3` vacía. No toca `vector_store`.

### Paso 3 — Generar embeddings en la tabla nueva
Re-embeber los 2.037 chunks con `bge-m3` escribiendo **solo** en `vector_store_bge_m3`.
Coste 0 €; ~11 min a los 3,15 chunks/s medidos. `vector_store` queda intacta.

### Paso 4 — Validar antes de conmutar
- Recuento: filas en la tabla nueva == chunks esperados; sin nulos; `vector_dims` = 1024.
- Los 255 casos contra la tabla nueva: exigir exactitud ≥ 0,93 y falsos grounding ≤ 4.
- Los 14 ataques: siguen `BLOCKED`.
- Playwright: reejecutar al menos los escenarios 19, 20, 21 y el de IA general.

### Paso 5 — Conmutación
```yaml
insurance-ai:
  ai:
    embedding-provider: ollama
    embedding-model: bge-m3:latest
  rag:
    vector-table: vector_store_bge_m3
    semantic:
      similarity-threshold: 0.65
spring:
  ai:
    model:
      embedding: ollama
```
Cuatro propiedades y un reinicio. Ningún dato se mueve.

### Paso 6 — Monitorización
`rag.embedding.latency{provider=ollama,outcome}`, `rag.retrieval.latency`,
`rag.grounding.count`, `rag.no_answer.count`. Una subida de `rag.no_answer.count` indicaría
umbral mal calibrado o identidad incompatible.

### Paso 7 — Rollback, sin pérdida
```yaml
insurance-ai: { ai: { embedding-provider: fake }, rag: { vector-table: vector_store } }
spring: { ai: { model: { embedding: none } } }
```
Reinicio. `vector_store` con sus 2.037 vectores `v1` nunca se tocó, así que el rollback es un
cambio de configuración, **no** una restauración. `vector_store_bge_m3` se **conserva**:
mantenerla permite reintentar sin re-embeber.

**Probado parcialmente**: el camino de ida está probado (el backend aislado arrancó con
`vector-table=vector_store_bge_m3` y respondió 255 casos). El rollback está probado en el sentido
de que la configuración por defecto sigue siendo la original y los 456 tests del backend se
ejecutan con ella — pero **no se ha ejecutado una conmutación y vuelta sobre el despliegue
principal**, porque eso exigiría migrarlo primero.

---

## 4. Identidad del embedding — parcialmente resuelta

| Componente de identidad | Estado |
|---|---|
| Proveedor | ✅ `descriptor().provider()` = `ollama` |
| Modelo | ✅ `bge-m3:latest` |
| Revisión / digest | ⚠️ se usa el **tag** (`bge-m3:latest`), no el digest sha256 |
| Dimensiones | ✅ 1024, verificadas contra el modelo al arrancar |
| Versión de preprocesamiento | ⚠️ implícita en el tag |
| Política de normalización | ❌ no registrada |
| Prefijos query/document | ❌ no registrados (`bge-m3` no los usa; `nomic` **sí**) |
| Colección / tabla | ✅ separación física por tabla |
| Fecha de generación | ❌ no registrada |

**Aislamiento efectivo logrado por separación física** (tabla por dimensión, lista blanca, misma
propiedad para las dos ramas), que es la barrera que de verdad impide mezclar espacios
vectoriales. La identidad **declarativa completa de siete campos sigue pendiente**, y con ella la
validación que rechace una consulta cuando la identidad registrada no coincide con la del puerto
activo. Un `tag` como `:latest` puede apuntar a pesos distintos con el tiempo, así que el digest
importa. Pendiente en `FASE_31_PENDIENTES_ES.md`.

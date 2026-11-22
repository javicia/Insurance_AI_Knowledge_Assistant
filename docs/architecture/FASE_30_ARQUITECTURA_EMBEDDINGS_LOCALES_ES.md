# FASE 30 — Arquitectura para embeddings locales intercambiables

Fecha: 2026-11-22. Estado: **DISEÑO ESPECIFICADO; ADAPTADOR NO IMPLEMENTADO EN ESTA FASE**.

Este documento es un diseño con un defecto arquitectónico real identificado y un procedimiento de
migración ejecutable. Distingue explícitamente lo diseñado de lo implementado.

---

## 1. Lo que ya existe y sirve

El puerto hexagonal es correcto y **no necesita cambios**:

```java
public interface EmbeddingModelPort {
    EmbeddingVector embed(String text);
    EmbeddingModelDescriptor descriptor();
}
```

Dominio y aplicación nunca ven Spring AI, ni Ollama, ni HTTP. Añadir un proveedor local es
**añadir un adaptador**, no construir una segunda arquitectura RAG. Implementaciones actuales:
`FakeEmbeddingModelAdapter` y `OpenAiEmbeddingModelAdapter`.

---

## 2. Defecto arquitectónico encontrado: el proveedor de embeddings está acoplado al de chat

`OpenAiEmbeddingModelAdapter` se activa con:

```java
@ConditionalOnExpression("'${insurance-ai.ai.provider}' != 'fake'")
```

y `FakeEmbeddingModelAdapter` con `havingValue = "fake"` sobre la **misma** propiedad.

Consecuencia: **`insurance-ai.ai.provider` elige simultáneamente el modelo de chat y el de
embeddings**, de modo que la combinación que esta fase demuestra como mejor —embeddings locales
`bge-m3` + chat `fake` para tests deterministas— **no es expresable hoy**. Hay que elegir: o todo
fake, o todo OpenAI.

Esto no se había detectado antes porque hasta la FASE 30 solo existían dos proveedores y ambos
coincidían en ambos planos.

### Corrección propuesta

Separar la propiedad:

```yaml
insurance-ai:
  ai:
    provider: fake              # modelo de CHAT (sin cambios)
    embedding-provider: fake    # NUEVO: fake | openai | ollama
```

y sustituir las condiciones por `@ConditionalOnProperty(prefix="insurance-ai.ai",
name="embedding-provider", havingValue=...)` en los tres adaptadores. Elimina la
`@ConditionalOnExpression` con negación, que era el síntoma del acoplamiento.

**No implementado en esta fase**: cambia la activación de beans existentes y exige revisar las
suites que fijan `insurance-ai.ai.provider` esperando el bean de OpenAI. Es un cambio contenido
pero no trivial, y la fase priorizó la medición.

---

## 3. Identidad del embedding — el control que evita el daño real

El riesgo concreto no es elegir mal el modelo: es **consultar vectores de un modelo con la
consulta de otro**, que no falla, simplemente devuelve resultados sin sentido.

`EmbeddingModelDescriptor` actual: `(provider, model, modelVersion, dimensions, similarityMetric)`.
Insuficiente. La FASE 29 ya forzó un `v1`→`v2` por un cambio de tokenización, y el enunciado
advierte con razón: *"No utilices únicamente `v1` o `v2` como identificador universal"*.

### Componentes que deben formar la identidad

| Componente | Por qué es identidad | Valor para `bge-m3` |
|---|---|---|
| `provider` | Distingue implementaciones | `ollama` |
| `model` | — | `bge-m3` |
| `modelDigest` | Ollama expone el digest; dos *tags* iguales pueden tener pesos distintos | `sha256:…` de `/api/tags` |
| `dimensions` | Un desajuste es irrecuperable | **1024** |
| `normalized` | Cambia la escala del coseno | normalizado |
| `queryPrefix` / `documentPrefix` | **Mismo modelo con otros prefijos = otro espacio vectorial** | `""` / `""` (para `nomic`: `search_query: ` / `search_document: `) |
| `preprocessingVersion` | Captura cambios de tokenización como el de la FASE 29 | `v1` |

El caso de los prefijos no es teórico: `nomic-embed-text` los exige, y usarlos en la ingesta pero
no en la consulta (o al revés) degrada silenciosamente la recuperación sin ningún error.

### Validación obligatoria antes de consultar pgvector

1. Leer la identidad registrada en `vector_store.metadata` (ya persiste `embeddingModel`,
   `embeddingModelVersion`, `embeddingDimension`).
2. Compararla con `embeddingModelPort.descriptor()`.
3. Si difiere, **fallar explícitamente** (`IllegalStateException`) en lugar de buscar.

Falla ruidosa y no degradada: una búsqueda contra vectores incompatibles devuelve resultados
plausibles y equivocados, que es el peor modo de fallo posible en un asistente que cita
documentos.

**Estado actual**: el despliegue principal tiene esta incoherencia **ahora mismo** — el código
emite `v2` y la base contiene `v1` (herencia de la FASE 29). Es tolerable porque la rama semántica
no fundamenta nada con el proveedor fake, pero demuestra que la validación es necesaria y no
hipotética.

---

## 4. Cómo se garantizó el aislamiento en esta fase

Sin tocar el backend, mediante separación física:

| Mecanismo | Implementación |
|---|---|
| Base separada | `fase30-eval-postgres`, puerto 5434, volumen y proyecto Compose propios |
| Tabla por modelo | `emb_bge_m3_latest` `vector(1024)`, `emb_nomic_embed_text_latest` `vector(768)` |
| Dimensión validada | El script aborta si Ollama devuelve una dimensión distinta de la esperada |
| Origen protegido | `PGOPTIONS=-c default_transaction_read_only=on` |
| Prefijos por modelo | Tabla `MODEL_PREFIXES`, aplicada igual en ingesta y consulta |

**Ninguna tabla puede contener vectores de dos modelos**, así que una consulta no puede mezclarlos
ni por error de programación. Es aislamiento por construcción, no por convención.

---

## 5. Procedimiento de migración a `bge-m3` (requiere autorización explícita)

**No ejecutar sin autorización.** Diseñado para ser reversible en cada paso.

### Prerrequisito: implementar lo de §2 y §3

Sin la separación de `embedding-provider` y sin la validación de identidad, la migración no es
segura.

### Paso 1 — Copia de seguridad verificada
```bash
docker exec insurance-ai-postgres pg_dump -U insurance_ai -d insurance_ai -Fc \
  -f /tmp/pre-bge-m3.dump
docker cp insurance-ai-postgres:/tmp/pre-bge-m3.dump ./backups/
```
Verificar tamaño y restaurabilidad en una base desechable **antes** de continuar.

### Paso 2 — Almacenamiento paralelo, no sustitución
Migración `V10__bge_m3_vector_store.sql` que **añade** `vector_store_bge_m3` con
`embedding vector(1024)` e índice HNSW coseno. **La tabla `vector_store` original no se toca**:
`vector(1536)` permanece, y con ella la posibilidad de rollback instantáneo.

### Paso 3 — Generar embeddings
Re-embeber los chunks con `bge-m3` escribiendo **solo** en la tabla nueva. Coste 0 €, ~9 min para
1.650 chunks (medido). Para los 2.037 del despliegue, ~11 min.

### Paso 4 — Validar antes de conmutar
- Recuento: filas en la tabla nueva == chunks esperados.
- Sin nulos; dimensión 1024 en todas.
- Identidad registrada por chunk.
- Re-ejecutar los 255 casos **contra la tabla nueva** y comprobar R@8 ≥ 0,88 y MRR ≥ 0,55.
- Comprobar los 14 ataques: siguen `BLOCKED`.
- Comprobar que los falsos grounding **no aumentan** respecto a la línea base.

### Paso 5 — Conmutación controlada
```yaml
insurance-ai:
  ai:
    embedding-provider: ollama
  rag:
    semantic:
      similarity-threshold: 0.65   # calibrado para bge-m3; 0.75 rechazaría el 91 % de la evidencia
```
Un solo contenedor, con los dos almacenamientos vivos.

### Paso 6 — Monitorización
`rag.embedding.latency{provider=ollama}`, `rag.retrieval.latency`, `rag.grounding.count`,
`rag.no_answer.count`. Alerta si `rag.no_answer.count` sube: indicaría umbral mal calibrado o
identidad incompatible.

### Paso 7 — Rollback sin pérdida
Revertir `embedding-provider` a `fake`/`openai` y `similarity-threshold` a su valor previo. La
tabla original sigue intacta, así que el rollback es un cambio de configuración y un reinicio.
**La tabla nueva no se borra**: conservarla permite reintentar sin re-embeber.

---

## 6. Requisitos del enunciado y su estado

| Requisito | Estado |
|---|---|
| Mantener el adaptador fake para tests | ✅ intacto |
| Añadir adaptador local | ❌ **no implementado** (diseño en §2) |
| Sin dependencias de Ollama en dominio/casos de uso | ✅ el puerto ya lo garantiza |
| Versionar embeddings | ⚠️ existe `modelVersion`; insuficiente, ampliación en §3 |
| Registrar modelo / dimensiones / preprocesamiento | ⚠️ parcial: `vector_store.metadata` ya guarda tres de los siete componentes |
| Detectar incompatibilidades antes de consultar | ❌ **no implementado** (§3) |
| Fallar explícitamente ante modelo incompatible | ❌ **no implementado** |
| Mantener observabilidad | ✅ `rag.embedding.latency{provider}` ya separa proveedores |
| Reutilizar Spring AI si procede | Spring AI 2 incluye `OllamaEmbeddingModel`; **preferible** a un cliente HTTP propio |
| No crear arquitectura paralela | ✅ el aislamiento se logró fuera del backend, sin tocarlo |

**Resumen honesto**: el aislamiento y la medición se consiguieron **sin modificar el backend**,
que era la vía de menor riesgo. La integración en el backend queda especificada y pendiente, con
el defecto de acoplamiento de §2 documentado como prerrequisito.

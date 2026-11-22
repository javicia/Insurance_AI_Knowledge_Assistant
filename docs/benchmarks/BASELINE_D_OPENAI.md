# BASELINE D — OpenAI real

## Estado: **BLOCKED / NOT EXECUTED**

No se ha ejecutado ninguna medición con el proveedor real, y **no se ha inventado ninguna cifra**.
Todas las métricas que dependían de OpenAI quedan marcadas UNKNOWN más abajo, cada una con el
motivo concreto.

Las líneas base A, B y C permanecen **intactas**: ningún fichero de resultados histórico se ha
modificado, y el almacén vectorial con embeddings `fake` no se ha tocado (verificado: 44
documentos / 2.037 vectores antes y después de este trabajo).

## 1. Motivo del bloqueo, con evidencia

La clave `OPENAI_API_KEY` **existe y es válida**, pero la cuenta **no tiene cuota disponible**.

| Comprobación | Resultado |
|---|---|
| `GET /v1/models` (no factura) | **HTTP 200** — la clave autentica; 118 modelos visibles, incluidos `gpt-4o-mini` y `text-embedding-3-small` |
| `POST /v1/embeddings` — intento 1 | HTTP 429 `insufficient_quota` |
| `POST /v1/embeddings` — intento 2 (+20 s) | HTTP 429 `insufficient_quota` |
| `POST /v1/embeddings` — intento 3 (+40 s) | HTTP 429 `insufficient_quota` |
| `POST /v1/chat/completions` | HTTP 429 `insufficient_quota` |

La distinción importa y por eso se comprobó explícitamente: un 429 puede ser un límite de
frecuencia (transitorio, se resuelve reintentando) o agotamiento de crédito (persistente). El
campo `type`/`code` devuelto es `insufficient_quota` en los cuatro casos, y los reintentos
espaciados dieron el mismo resultado. **No es transitorio y reintentar no lo resolverá.**

En ningún momento se ha impreso, registrado ni copiado el valor de la clave. La auditoría de
credenciales solo reporta presencia, longitud y prefijo enmascarado.

## 2. Qué falta exactamente para desbloquear

Una sola cosa: **crédito en la cuenta de OpenAI** asociada a esa clave (o una clave de otra cuenta
con saldo). Nada más está pendiente — el resto de la infraestructura para la línea base D está
construida, validada y lista para ejecutarse.

Coste estimado de la ejecución completa: los 255 casos más la reingesta de 1.650 fragmentos con
`text-embedding-3-small` y `gpt-4o-mini`. Con las tarifas configuradas en
`benchmarks/config/benchmark.yaml` (**CONFIGURED RATE**, no precio oficial verificado: 0,02 USD/1M
tokens de embedding; 0,15 / 0,60 USD/1M tokens de entrada/salida de LLM) el orden de magnitud es
de **unidades de dólar**, no decenas. No se afirma una cifra exacta porque depende del recuento
real de tokens, que es precisamente lo que no puede medirse todavía.

## 3. Qué SÍ se ha entregado

### 3.1 Entorno aislado, construido y validado

`docker-compose.baseline-d.yml` levanta un backend independiente
(`insurance-ai-backend-openai`, puerto 18081) contra una **base de datos PostgreSQL separada**
(`insurance_ai_baseline_d`) dentro del mismo servidor — la opción preferente del encargo.

Motivo del aislamiento: los vectores `fake` (hashing de bolsa de palabras) y los de OpenAI viven
en espacios distintos; una similitud coseno entre ambos no significa nada. Reingerir sobre el
mismo `vector_store` corrompería la recuperación **y** destruiría de forma irreversible el estado
bajo el que se midieron B y C.

Validado de extremo a extremo:

| Comprobación | Resultado |
|---|---|
| Flyway sobre la base nueva | 7 / 7 migraciones aplicadas, todas `success` |
| Backend arrancado | `healthy` a los 80 s |
| Proveedor activo | `openai` |
| Umbral semántico | `0.75` (**valor de producción**, no el 0,45 del overlay de corpus) |
| `vector_store` de la base nueva | **0 documentos, 0 fragmentos, 0 vectores** — sin contaminación histórica |
| Base original tras el ejercicio | 44 documentos / 2.037 vectores — **intacta** |

El umbral se fija deliberadamente en 0,75: el 0,45 del overlay se bajó para compensar embeddings
falsos, y reutilizarlo con embeddings reales falsearía la comparación a favor de OpenAI.

### 3.2 Corpus confirmado antes de empezar

| | Exigido | Medido |
|---|---|---|
| Documentos | 26 | **26** |
| Páginas PDF | 119 | **119** |
| Palabras | 48.964 | **48.964** |
| Fragmentos objetivo | 1.650 | pendiente de la reingesta real |

Los 18 documentos históricos ajenos al corpus **no** se incluyen: la base de la línea base D
arranca vacía, así que por construcción no pueden colarse.

### 3.3 Un defecto real encontrado gracias al proveedor caído

Que OpenAI devolviera 429 permitió ejercitar el camino de fallo del proveedor **real**, no un
mock. Resultado medido (`scripts/probe_provider_failure.py`):

```
HTTP devuelto al cliente : 200
Cuerpo                   : "I do not have sufficient information in the available documentation..."
Auditoría                : NO_ANSWER | provider=openai
```

Es decir: **una caída del proveedor se presentaba como «la documentación no contiene la
respuesta»**. Causa raíz en `HybridRetrievalService`: la rama semántica lanzaba excepción, la
rama léxica «tenía éxito» devolviendo cero filas, y la degradación elegante seguía adelante con
un conjunto vacío.

Por qué importa más allá de la estética:

- el registro de auditoría (artefacto de cumplimiento del AI Act) afirma un resultado de
  recuperación que nunca ocurrió;
- `rag.no_answer.count` se infla durante una caída, y `outcome=ERROR` no se dispara nunca, así
  que la monitorización no puede distinguir «hueco en el corpus» de «proveedor caído»;
- al usuario se le dice que la documentación no cubre su pregunta cuando en realidad no se
  consultó nada.

Corregido: si una rama **falla** y la superviviente no aporta ninguna evidencia, se lanza
`TransientProcessingException` → **HTTP 503**, que es la respuesta semánticamente correcta.

El cambio es deliberadamente estrecho. Cuando ambas ramas tienen éxito y simplemente no encuentran
nada, sigue siendo un `NO_ANSWER` legítimo e intacto — que es exactamente el caso de las líneas
base con proveedor `fake` (la rama léxica devuelve cero filas pero **no falla**), por lo que B y C
siguen siendo reproducibles sin variación. Dos tests nuevos fijan ambos lados de la regla.

## 4. Métricas exigidas — estado real

Todas las que siguen requieren cuota y quedan **UNKNOWN**. No se sustituyen por ceros: un cero
sería indistinguible de una medición real de valor nulo.

| Sección | Métrica | Estado |
|---|---|---|
| Corpus | 26 doc / 119 pág / 48.964 palabras | **MEDIDO** |
| Corpus | 1.650 fragmentos / embeddings reales | UNKNOWN — requiere reingesta |
| Modelo | `gpt-4o-mini`, `text-embedding-3-small` | CONFIGURADO, no ejercitado |
| 255 casos | Recall@1/3/5/8, MRR | UNKNOWN |
| 255 casos | Grounding, no-answer, blocked, DEPENDS | UNKNOWN |
| Latencia | p50 / p95 / p99 request, retrieval, embedding, reranking, LLM | UNKNOWN |
| Throughput | peticiones/s con OpenAI | UNKNOWN |
| Tokens | input / output / total | UNKNOWN |
| Coste | por petición, total, mensual proyectado | UNKNOWN |
| Ingesta | latencias, documents/min, chunks/s, embeddings/s | UNKNOWN |
| Recursos | CPU/RAM bajo carga OpenAI | UNKNOWN |
| Cold/warm | arranque y primera petición con OpenAI | UNKNOWN |
| Trazas | span HTTP de OpenAI en Jaeger | UNKNOWN — no hay llamada con éxito que trazar |
| Concurrencia | 1 / 5 / 10 con OpenAI | UNKNOWN |
| Error handling | comportamiento ante proveedor caído | **MEDIDO** (§3.3) |

### Tokens: por qué siguen sin estar VIVAS

`rag.llm.tokens.input/output/total` continúan **DECLARADAS + DESPLEGADAS pero NO VIVAS**. El
encargo preveía que pasaran a VIVAS con OpenAI, y no ha ocurrido — pero no por un defecto de
instrumentación: sin cuota no hay ni una sola respuesta del LLM, y por tanto ningún `usage` que
registrar. `recordTokenUsage` no registra nada cuando el proveedor no informa de uso, en lugar de
registrar ceros. El comportamiento es correcto; lo que falta es la llamada.

## 5. Fake vs OpenAI

| Métrica | Fake (línea base B) | OpenAI (línea base D) | Diferencia |
|---|---:|---:|---:|
| Request p50 | 390 ms | NO EJECUTADO | — |
| Request p95 | 831 ms | NO EJECUTADO | — |
| Retrieval p95 | medido en B | NO EJECUTADO | — |
| Embedding p95 | medido en B (adaptador falso) | NO EJECUTADO | — |
| LLM p95 | medido en B (sustituto) | NO EJECUTADO | — |
| Recall@8 | 0,477 | NO EJECUTADO | — |
| MRR | 0,234 | NO EJECUTADO | — |
| Grounding | 128/153 (0,837) | NO EJECUTADO | — |
| No-answer | 26/58 (0,448) | NO EJECUTADO | — |
| Tokens | UNKNOWN | UNKNOWN | — |
| Coste | UNKNOWN | UNKNOWN | — |

**No se rellena ninguna casilla por estimación.** Una comparación con una mitad inventada no es
una comparación.

## 6. Cómo ejecutar la línea base D cuando haya cuota

```bash
# 1. Crear la base aislada una sola vez
docker exec insurance-ai-postgres psql -U insurance_ai -d insurance_ai \
  -c "CREATE DATABASE insurance_ai_baseline_d OWNER insurance_ai;"
docker exec insurance-ai-postgres psql -U insurance_ai -d insurance_ai_baseline_d \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 2. Levantar el backend aislado con proveedor real
docker compose -f docker-compose.yml -f docker-compose.corpus-es.yml \
               -f docker-compose.hostports.yml -f docker-compose.baseline-d.yml \
               up -d backend-openai

# 3. Comprobar que la clave responde ANTES de ingerir nada
python scripts/probe_provider_failure.py --base-url http://localhost:18081

# 4. Reingerir el corpus contra el backend aislado, y medir
python benchmarks/run_quality.py --topology backend   # apuntando a 18081
python scripts/audit_metrics.py
```

## 7. Veredicto

**BLOCKED — no evaluable.**

No procede emitir GREEN/YELLOW/RED sobre el rendimiento con proveedor real: no se ha medido ni un
solo dato con él. Emitir un veredicto sería inventarlo.

Lo que sí puede afirmarse con evidencia:

- el **aislamiento** exigido está construido y verificado, y ninguna línea base histórica se ha
  contaminado;
- el **corpus** coincide exactamente con las cifras oficiales (26 / 119 / 48.964);
- el sistema **falla de forma segura** ante un proveedor caído — y no lo hacía hasta esta fase,
  donde el fallo real permitió encontrar y corregir el defecto.

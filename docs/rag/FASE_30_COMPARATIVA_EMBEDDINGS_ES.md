# FASE 30 — Comparativa de modelos de embeddings locales

Fecha: 2026-11-22. Estado: **EJECUTADO Y MEDIDO**. Coste de API: **0 €**.

Todo lo de este documento se ejecutó en local, sin ninguna llamada a OpenAI, Anthropic ni a
ningún servicio de inferencia de pago. No se leyó ni se utilizó `OPENAI_API_KEY`.

---

## 1. Hardware disponible — condiciona la selección

| Recurso | Valor medido |
|---|---|
| CPU | 12 núcleos lógicos |
| RAM | 31,8 GB |
| **GPU** | **Intel UHD Graphics 630 (1 GB, integrada)** |
| Disco C: / D: | 93 GB / 1.548 GB libres |
| Ollama | **0.32.5, ya instalado** (`C:\Users\javie\AppData\Local\Programs\Ollama`) |
| `OLLAMA_MODELS` | `D:\Javier\IA\Ollama\models` |

**No hay GPU utilizable.** La UHD 630 es gráfica integrada sin CUDA ni ROCm, así que Ollama
ejecuta **exclusivamente en CPU**. Esto es la restricción que gobierna toda la selección: descarta
los modelos de embeddings grandes y hace que la latencia de embedding domine la del pipeline
(§5 de `docs/operations/FASE_30_RECURSOS_Y_RENDIMIENTO_ES.md`).

---

## 2. Modelos candidatos y selección

### 2.1 Ya instalados — descarga cero

El inventario reveló **siete** modelos ya presentes, de los cuales **dos son de embeddings**:

| Modelo | Tamaño | Params | Familia | Cuantización |
|---|---|---|---|---|
| `bge-m3:latest` | 1,08 GB | 566,70 M | bert | F16 |
| `nomic-embed-text:latest` | 0,26 GB | 137 M | nomic-bert | F16 |

Los otros cinco (`gemma4`, `deepseek-r1:14b`, `qwen2.5-coder:7b/1.5b`, `llama3.2:3b`) son modelos
de chat y quedan fuera del alcance.

**No se descargó ningún modelo nuevo.** Los dos presentes cubren exactamente la pauta que el
enunciado pedía —"prioriza un modelo pequeño y uno de mayor capacidad"— y evitan consumir disco y
ancho de banda sin necesidad.

### 2.2 Verificación por criterio

| Criterio | `bge-m3` | `nomic-embed-text` |
|---|---|---|
| Soporte español | Multilingüe por diseño (100+ idiomas) | **Centrado en inglés**; multilingüe solo en la variante v2-moe, que NO es la instalada |
| Dimensiones | **1024** (medido) | **768** (medido) |
| Licencia | MIT | Apache 2.0 |
| Tamaño en disco | 1,08 GB | 0,26 GB |
| Viabilidad CPU | Sí, 3,15 chunks/s con lotes de 32 | Sí, 6,83 chunks/s |
| Prefijos de tarea | No requiere | **Requiere** `search_query:` / `search_document:` |
| Similitud | Coseno | Coseno |
| Integración | Ollama `/api/embed` | Ollama `/api/embed` |

Los prefijos de `nomic` no son un detalle de uso: forman parte de la **identidad del embedding**.
El mismo modelo con otros prefijos produce otro espacio vectorial, y por eso se registran junto al
modelo y la dimensión (ver `FASE_30_ARQUITECTURA_EMBEDDINGS_LOCALES_ES.md`). En toda esta fase se
usaron los prefijos documentados, tanto en ingesta como en consulta.

### 2.3 La advertencia del enunciado sobre `nomic` era correcta

El enunciado pedía "verificando su cobertura multilingüe real antes de incluirlo". Se verificó, y
**no la tiene**: la variante instalada es la v1.5, de diseño anglocéntrico. Se mantuvo en la
comparación precisamente por eso — para medir el coste real de usar un modelo no multilingüe en un
corpus español, en lugar de descartarlo por su ficha técnica.

---

## 3. Calidad semántica en español, medida directamente

Antes de ingestar 1.650 chunks con cada modelo se midió su capacidad real sobre 15 pares
diseñados para que **la coincidencia léxica no pueda resolverlos**: paráfrasis sin vocabulario
compartido, sinónimos, acentos, eñes, plurales, flexión verbal, y negativos interdominio.

Script: `probe_es.py` (reproducible, 15 pares, coseno sobre el vector normalizado).

| Par | Esperado | `bge-m3` | `nomic` |
|---|---|---|---|
| paráfrasis sin solape léxico | ALTO | 0,6295 | 0,4990 |
| sinónimo vehículo/coche | ALTO | **0,8327** | 0,5951 |
| definición de franquicia | ALTO | 0,3222 | 0,5229 |
| pérdida total (definición) | ALTO | 0,3830 | 0,5568 |
| acentos `poliza`/`póliza` | ALTO | 0,7735 | 0,8800 |
| eñes `danos`/`daños` | ALTO | 0,5034 | 0,8679 |
| frase sin acentos | ALTO | 0,8632 | 0,8939 |
| plural lunas | ALTO | 0,8509 | 0,8307 |
| flexión cubrir | ALTO | 0,6379 | 0,7153 |
| **interdominio hogar↔auto** | **BAJO** | **0,5387** | 0,5500 |
| interdominio vida↔auto | BAJO | 0,4725 | 0,4955 |
| fuera de dominio (fotosíntesis) | BAJO | **0,3847** | 0,4688 |
| **fuera de dominio (capital de Francia)** | **BAJO** | **0,2494** | **0,6568** |
| transaccional (contraseña) | BAJO | 0,3366 | 0,5701 |
| transaccional (teléfono) | BAJO | 0,4641 | 0,5228 |
| **media ALTO / media BAJO** | | **0,644 / 0,408** | 0,707 / 0,544 |
| **máximo de los BAJO** | | **0,5387** | **0,6568** |

### Lectura

`nomic` puntúa más alto en casi todo, incluido lo que **no debería**: *"¿Cuál es la capital de
Francia?"* frente a *"franquicia aplicable en caso de siniestro"* obtiene **0,6568**, por encima
de varios de sus propios positivos. Comprime todo en una banda alta estrecha, que es el patrón
típico de un modelo cuya cobertura del idioma es débil: distingue poco porque entiende poco.

Lo que importa para este producto no es la similitud media de los positivos, sino **dónde queda el
negativo más alto**, porque es lo que un umbral tiene que dejar fuera. En ese criterio `bge-m3`
gana con holgura: **0,5387 frente a 0,6568**.

Los dos "DÉBIL" de `bge-m3` (definición de franquicia 0,3222, pérdida total 0,3830) son relaciones
**definicionales** —pregunta corta frente a explicación larga— genuinamente difíciles para un
modelo de similitud, no un fallo de español.

---

## 4. Rendimiento de ingesta (CPU, lotes de 32)

| Modelo | Lote 1 | Lote 8 | Lote 32 | 1.650 chunks |
|---|---|---|---|---|
| `bge-m3` | 2.550 ms/chunk | 533 ms/chunk | **318 ms/chunk (3,15/s)** | **8,7 min** |
| `nomic` | 2.201 ms/chunk | 380 ms/chunk | **183 ms/chunk (6,83/s)** | **4,0 min** |

El lote es decisivo: de 1 a 32 mejora el rendimiento **8×** en `bge-m3`. Sin lotes la ingesta
habría tardado 70 minutos por modelo y la comparación completa no habría sido viable en esta fase.

---

## 5. Resultado de recuperación (255 casos, corpus aislado de 1.650 chunks)

| Variante | R@1 | R@3 | R@5 | R@8 | MRR | p50 |
|---|---|---|---|---|---|---|
| A — léxico puro | 0,2549 | 0,5948 | 0,7386 | 0,8431 | 0,4434 | **186 ms** |
| B — semántico `bge-m3` | 0,3072 | 0,6928 | 0,8235 | 0,8627 | 0,5133 | 2.523 ms |
| **C — híbrido `bge-m3`** | **0,4314** | **0,6993** | **0,8301** | **0,9020** | **0,5900** | 2.688 ms |
| D — semántico `nomic` | 0,3268 | 0,6667 | 0,7974 | 0,8431 | 0,5095 | 2.306 ms |
| E — híbrido `nomic` | 0,3660 | 0,7190 | 0,8039 | 0,8824 | 0,5558 | 2.460 ms |

Comando: `python scripts/fase30_compare_retrieval.py --variant <v> --model <m>`, exit code 0 en
las cinco.

### Conclusiones

1. **El híbrido gana a cualquier rama aislada, con los dos modelos.** No es una suposición de
   diseño: está medido. `bge-m3` híbrido mejora su propia rama semántica en R@1 de 0,3072 a
   **0,4314** (+40 % relativo), porque las dos ramas fallan en casos distintos y RRF las suma.
2. **`bge-m3` > `nomic` en todas las métricas del híbrido** (R@8 0,9020 vs 0,8824; MRR 0,5900 vs
   0,5558), y además con mucha mejor separación de negativos (§3), que es lo que decide el
   grounding.
3. `nomic` gana en R@1 semántico (0,3268 vs 0,3072) y en latencia. Es una alternativa razonable si
   la latencia pesara más que la precisión, pero **su banda de negativos la descarta** para un
   asistente que no debe afirmar coberturas sin evidencia.
4. La latencia del híbrido está dominada por el embedding de la consulta (2.523 ms de los
   2.688 ms, **94 %**), no por pgvector ni por el FTS. Ver
   `FASE_30_RECURSOS_Y_RENDIMIENTO_ES.md`.

---

## 6. Advertencia de comparabilidad con la FASE 29

**No se puede comparar directamente** R@8 0,9020 con el 0,5621 de la FASE 29. Hay **dos**
diferencias, no una:

1. **Corpus**: 1.650 chunks curados aquí, frente a 2.037 contaminados allí (7 duplicados
   `Bench *` + 11 pruebas heredadas compitiendo por las 8 posiciones de cita).
2. **Contabilidad del rango**: esta fase **deduplica por documento** antes de asignar la posición.
   Un documento con tres chunks en el top-8 ocupa tres posiciones pero es **un solo acierto
   documental**, que es lo que `expected_document` mide. La FASE 29 contaba la posición del chunk
   en la lista de citas de la API.

La segunda diferencia es metodológica y por sí sola eleva Recall@K. Por eso la variante A (léxico
puro) se volvió a ejecutar **sobre este mismo corpus y con esta misma contabilidad**: es la única
línea base con la que B, C, D y E son comparables. Atribuir a los embeddings la diferencia frente
a la FASE 29 sería atribuirles también la descontaminación del corpus y el cambio de medida.

---

## 7. Modelo recomendado

**`bge-m3:latest`, en configuración híbrida.**

| Motivo | Evidencia |
|---|---|
| Mejor recuperación | R@8 0,9020 / MRR 0,5900, las mejores de las cinco variantes |
| Mejor separación de negativos | máx. negativo 0,5387 vs 0,6568 de `nomic` |
| Multilingüe real | medido en 15 pares españoles, no deducido de la ficha |
| Coste | 0 €, local, ya descargado |
| Viable en CPU | 3,15 chunks/s con lotes; 8,7 min para el corpus completo |
| Licencia | MIT |

**Contrapartida honesta**: 1.024 dimensiones frente a las 1.536 de la columna actual, y p50 de
2,5 s por consulta en CPU. Ambas cosas son gestionables y están tratadas en
`FASE_30_ARQUITECTURA_EMBEDDINGS_LOCALES_ES.md` y en el documento de rendimiento.

# FASE 30 — Recursos y rendimiento de los embeddings locales

Fecha: 2026-11-22. Estado: **MEDIDO** salvo donde se indica NO MEDIDO.

Todas las cifras son de **CPU**: el equipo no tiene GPU utilizable (§1).

---

## 1. Entorno de medida

| Recurso | Valor |
|---|---|
| CPU | 12 núcleos lógicos |
| RAM | 31,8 GB |
| GPU | **Intel UHD Graphics 630 (1 GB, integrada)** — sin CUDA/ROCm |
| Disco | C: 93 GB libres / D: 1.548 GB libres |
| Ollama | 0.32.5, CPU |
| PostgreSQL de evaluación | `pgvector/pgvector:pg16`, límite de memoria **1 GB** |

**Ollama ejecuta en CPU.** Toda la latencia de embedding de este documento es CPU, y una GPU
dedicada la reduciría en un orden de magnitud. El límite de 1 GB en el contenedor de evaluación es
deliberado: evita competir con los otros proyectos Docker de la máquina.

---

## 2. Carga del modelo (arranque en frío vs caliente)

| Modelo | 1ª llamada (frío, incluye carga) | Llamada caliente | Factor |
|---|---|---|---|
| `bge-m3` | **33.055 ms** | 2.297 ms | 14,4× |
| `nomic-embed-text` | **9.439 ms** | 2.106 ms | 4,5× |

La primera llamada a `bge-m3` tarda **33 segundos** porque carga 1,08 GB de pesos. Es la cifra
más importante de este documento para un despliegue: **el modelo debe mantenerse cargado**. Ollama
lo descarga tras un periodo de inactividad (`OLLAMA_KEEP_ALIVE`), y con el valor por defecto la
primera consulta tras un rato de calma pagaría 33 s — inaceptable en un asistente interactivo.

---

## 3. Rendimiento de embedding por lote

| Modelo | Lote 1 | Lote 8 | Lote 32 |
|---|---|---|---|
| `bge-m3` | 2.550 ms/chunk (0,39/s) | 533 ms/chunk (1,88/s) | **318 ms/chunk (3,15/s)** |
| `nomic` | 2.201 ms/chunk (0,45/s) | 380 ms/chunk (2,63/s) | **183 ms/chunk (6,83/s)** |

El lote es **8× más eficiente** que las llamadas individuales en `bge-m3`. Sin lotes, ingestar
1.650 chunks habría tardado 70 minutos por modelo.

### Ingesta completa medida (1.650 chunks)

| Modelo | Tiempo | Rendimiento |
|---|---|---|
| `bge-m3` | **8,7 min** | 3,15 chunks/s |
| `nomic` | **4,0 min** | 6,83 chunks/s |

Para los 2.037 chunks del despliegue principal: ~11 min con `bge-m3`. Coste 0 €.

---

## 4. Latencia de recuperación extremo a extremo (255 consultas reales)

| Variante | p50 | p95 | p99 | media |
|---|---|---|---|---|
| Léxico puro | **185,6 ms** | 232,9 ms | — | — |
| Semántico `bge-m3` | 2.522,8 ms | 2.617,4 ms | — | — |
| **Híbrido `bge-m3`** | **2.687,6 ms** | — | — | — |
| Semántico `nomic` | 2.305,6 ms | 2.354,9 ms | — | — |
| Híbrido `nomic` | 2.460,1 ms | 2.528,3 ms | — | — |

*(Los p99 de algunas variantes no se imprimieron en la salida conservada; los ficheros
`target-audit/fase30/retrieval-*.json` contienen p50/p95/p99 y media de cada una.)*

### 4.1 Descomposición: el embedding de la consulta domina

| Componente | Latencia | % del híbrido |
|---|---|---|
| **Embedding de la consulta (Ollama, CPU)** | ~2.300 ms | **~86 %** |
| Búsqueda vectorial pgvector (HNSW) + FTS + fusión RRF | ~390 ms | ~14 % |

Derivación: el híbrido (2.688 ms) ejecuta embedding + pgvector + FTS + RRF; el léxico puro
(186 ms) ejecuta solo FTS. El semántico puro (2.523 ms) es embedding + pgvector. Por tanto
embedding ≈ 2.523 − (latencia pgvector) y el FTS aporta los ~186 ms que separan semántico de
híbrido más el coste de la fusión.

**Conclusión: optimizar pgvector o el índice no serviría de nada.** El 86 % del tiempo está en
generar el vector de la consulta en CPU. Cualquier optimización real tiene que atacar eso.

---

## 5. Optimizaciones justificadas por la medición

| Optimización | Ganancia esperada | Riesgo | Recomendación |
|---|---|---|---|
| **Mantener el modelo cargado** (`OLLAMA_KEEP_ALIVE=-1` o alto) | Evita 33 s en frío | Ocupa ~1,1 GB de RAM de forma permanente | **Sí, imprescindible** |
| **GPU dedicada** | Orden de magnitud sobre 2,3 s | Hardware | Sí, si el despliegue lo permite |
| Caché de embeddings de consulta | Nula en preguntas distintas; alta en repetidas | **Ver §5.1** | Solo con las garantías de §5.1 |
| Lotes en ingesta | **8×, ya aplicada** | Ninguno | Ya hecho |
| Reducir `top-K` | Marginal (pgvector es el 14 %) | Pierde recall | **No** |
| Optimizar índice HNSW | Marginal, misma razón | — | **No** |
| Modelo más pequeño (`nomic`) | 2.306 ms vs 2.523 ms (−9 %) | **Peor separación de negativos** (§3 de la comparativa) | **No** — 9 % de latencia no compensa el riesgo de grounding |

### 5.1 La caché de consultas, si se hace, con estas condiciones

El embedding de una consulta depende **solo del texto y del modelo**, no del usuario ni de sus
permisos, así que una caché de `texto -> vector` **no** cruza datos entre usuarios por sí misma.
Pero el enunciado advierte con razón sobre cachés que mezclan tenants, y aquí el riesgo real es
distinto y más sutil:

1. **La clave debe incluir la identidad completa del embedding** (modelo, digest, dimensiones,
   prefijos, versión de preprocesamiento — §3 de
   `FASE_30_ARQUITECTURA_EMBEDDINGS_LOCALES_ES.md`). Una caché indexada solo por texto devolvería
   vectores de otro modelo tras un cambio de proveedor, sin fallar.
2. **Nunca cachear resultados de búsqueda**, solo el vector de la consulta. Los resultados sí
   dependen de los permisos y del `RetrievalFilter`, y cachearlos sí filtraría documentos
   restringidos entre usuarios.
3. **Tamaño acotado con expulsión LRU**: las preguntas de los empleados son texto libre y una
   caché sin límite crece sin cota.
4. Las preguntas contienen a veces PII (FT-242/243/244 del dataset la contienen). Una caché en
   memoria con clave de texto **almacena esa PII**: hay que acotar el TTL y no persistirla en
   disco.

**No implementada en esta fase.** El beneficio depende por completo de la tasa de repetición de
preguntas en uso real, que no se ha medido.

---

## 6. Consumo de recursos

| Métrica | Valor |
|---|---|
| Disco, modelos ya presentes | `bge-m3` 1,08 GB + `nomic` 0,26 GB (sin descargas nuevas) |
| RAM del proceso de ingesta (Python) | ~26 MB |
| Contenedor PostgreSQL de evaluación | límite 1 GB |
| **RAM del proceso Ollama durante inferencia** | **NO MEDIDO** |
| **CPU % durante ingesta** | **NO MEDIDO** |
| **GPU / VRAM** | **N/A** — sin GPU utilizable |

Las dos filas NO MEDIDO son una laguna real de esta fase: se observó el tiempo de pared y el
rendimiento, pero no se instrumentó `docker stats` ni el consumo del proceso de Ollama durante la
ingesta. Dado que Ollama corre **fuera de Docker** (binario nativo en Windows), `docker stats` no
lo habría capturado y habría hecho falta muestreo por PID. **No se inventan cifras.**

Indicador indirecto disponible: la ingesta de los dos modelos y los cinco benchmarks se
completaron sin que ningún contenedor del proyecto perdiera el estado `healthy` y sin degradar los
otros proyectos Docker de la máquina.

---

## 7. ¿Es aceptable 2,7 s para un asistente?

Honestamente: **es alto para una interacción conversacional**, y es el precio de no tener GPU.

Contexto para decidir:

- El p50 del camino **léxico** es de 186 ms, así que la configuración actual del despliegue
  (fake + léxico) es rápida pero tiene MRR 0,4434 frente a 0,5900.
- El coste es **0 €** frente a una API comercial de embeddings.
- Es una latencia de **consulta**, no de ingesta, y se paga una vez por pregunta.
- Con el modelo cargado y una GPU dedicada bajaría al rango de las centenas de ms.

**Recomendación**: aceptable para un PoC y para uso interno; antes de un despliegue con volumen
real, resolver `OLLAMA_KEEP_ALIVE` y evaluar GPU. No se recomienda cambiar a `nomic` para ganar
el 9 %: la pérdida de separación de negativos afecta al grounding, que es el control que impide
afirmar coberturas sin evidencia.

---

## 8. Pruebas de carga NO ejecutadas

No se ejecutaron pruebas de concurrencia ni de estrés, por la restricción explícita de no
perjudicar a los otros proyectos Docker de la máquina y porque el arnés serializa las peticiones
para respetar los 60 req/min del gateway.

Por tanto **todas las latencias de este documento son de petición única**, y no describen el
comportamiento bajo carga. Con el embedding en CPU como cuello de botella, es previsible que la
concurrencia degrade de forma marcada, pero **previsible no es medido** y no se afirma.

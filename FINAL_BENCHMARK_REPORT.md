# Informe final de benchmarking — Insurance Knowledge Assistant

**Todas las cifras de este informe proceden de ejecuciones reales contra el stack desplegado.**
Los ficheros de resultados están en `benchmarks/results/`. Lo que no pudo medirse se marca
explícitamente como `NO MEDIDA` con su motivo; no hay ningún número estimado presentado como
observado.

---

## 1. Resumen ejecutivo

| Pregunta | Respuesta medida |
|---|---|
| ¿Cuánto tarda una pregunta? | **p50 569 ms**, p95 1.211 ms, p99 1.362 ms (1 usuario, a través del WAF) |
| ¿Cuántas preguntas/segundo soporta? | **10,5 rps** a 10 usuarios concurrentes, 0 % de error |
| ¿Cómo escala? | Lineal hasta 10 concurrentes; la latencia p95 **no** se degrada |
| ¿Cuánto tarda la ingesta de un documento? | **~41 s** de media hasta `EMBEDDED` (cota superior), subida 1,2 s |
| ¿Cuánta RAM consume el sistema? | **≈1.984 MiB** en reposo, los diez contenedores |
| ¿Cuánto CPU en reposo? | < 2 % por contenedor |
| ¿Cuál es el arranque en frío? | Contenedor sano en **42 s**; primera petición 1.482 ms frente a p50 caliente de 491 ms |
| ¿Cuál es el cuello de botella? | **No hay evidencia suficiente** para señalar uno con el proveedor `fake` — ver §12 |
| ¿Cuánto cuesta una consulta? | **NO MEDIDA** — requiere proveedor real (§9) |

**Advertencia que condiciona todo el informe**: el stack midió con `INSURANCE_AI_PROVIDER=fake`.
`FakeLlmAdapter` no realiza ninguna llamada de red. **Estas latencias no representan el
rendimiento de un LLM real**; representan el pipeline, la recuperación y la infraestructura.

## 2. Entorno

- Windows 11 + Docker Desktop (WSL2), 12 CPU lógicas, ~15,5 GiB disponibles para Docker.
- Commit `d6dd49e`; proveedor `fake`; umbral semántico `0.45` (overlay `corpus-es`).
- Corpus: 25 documentos españoles, 1.591 fragmentos, 1.591 embeddings. *(Estado en el momento
  de la medición. El corpus se amplió después a 26 documentos y 1.650 fragmentos; las cifras
  de rendimiento no se re-midieron, así que se dejan atribuidas al corpus que las produjo.)*
- **Contención conocida**: otros proyectos mantienen contenedores en marcha en la misma máquina
  (uno ocupa el puerto 8080). Es una fuente real de ruido y está declarada, no ignorada.

## 3. Metodología

Resumida aquí, detallada en `docs/benchmarks/BENCHMARK_METHODOLOGY.md`: arnés propio en Python
(no había k6/Gatling/JMeter en el proyecto ni en la máquina), modelo **cerrado** de carga,
percentiles por rango más cercano, respeto del rate limiting del gateway, calentamiento previo, y
registro del proveedor y umbral reales dentro de cada fichero de resultados.

## 4. Latencia de chat y concurrencia

Fichero: `2026-08-14T112240Z-concurrency.json` / `.csv`. 60 peticiones por nivel, a través del WAF.

| Concurrencia | Throughput | p50 | p95 | p99 | máx | Errores | Grounding |
|---|---|---|---|---|---|---|---|
| 1 | 1,71 rps | 569 ms | 1.211 ms | 1.362 ms | 1.426 ms | 0 % | 0,85 |
| 5 | 4,65 rps | 1.039 ms | 1.666 ms | 1.670 ms | 1.671 ms | 0 % | 0,85 |
| 10 | 10,47 rps | 984 ms | 1.198 ms | 1.204 ms | 1.498 ms | 0 % | 0,85 |

Observaciones respaldadas por los datos:

- **El throughput escala casi linealmente** de 1 a 10 concurrentes (1,71 → 10,47 rps).
- **La latencia p95 no se degrada** al subir la concurrencia; a 10 usuarios es incluso menor que a
  5. Con 60 muestras por nivel esa diferencia está dentro del ruido, así que la lectura honesta es
  «p95 estable en torno a 1,2–1,7 s», no «mejora con la carga».
- **Cero errores** en los tres niveles.
- **El grounding se mantiene en 0,85** en todos los niveles: la calidad de recuperación no se
  degrada bajo carga.

**25/50/100 concurrentes: NO MEDIDOS.** El límite de 60 req/min del gateway hace que una fase de
60 peticiones a 50 usuarios dure segundos y quede dominada por el limitador, no por el sistema. Se
prefiere no publicar un número que describiría el rate limiter.

## 5. Desglose por etapas del RAG

**NO MEDIDO desde el arnés.** El API expone una única latencia HTTP; el desglose por guardarraíles
/ recuperación / reranking / ensamblado / LLM / auditoría no es observable desde fuera.

Sí existe instrumentación interna real, verificada en el código:

- `rag.retrieval.latency` (Micrometer, etiqueta `outcome`) — `HybridRetrievalService`
- `rag.llm.latency` (etiquetas `provider`, `outcome`) — `AskInsuranceKnowledgeUseCase`
- Spans de OpenTelemetry por petición, incluidos los JDBC y los de Kafka productor/consumidor

Consultables en `/actuator/metrics` y en Jaeger. El arnés todavía **no** los agrega en sus
ficheros de resultados; queda como trabajo pendiente explícito, no como algo ya hecho.

## 6. Topología: coste de las capas de seguridad

Fichero: `2026-08-14T112454Z-topology.json`. 30 peticiones por topología, secuenciales.

| Topología | p50 | p95 | n |
|---|---|---|---|
| WAF → Gateway → Backend | 296 ms | 611 ms | 30 |
| Gateway → Backend | 381 ms | 1.048 ms | 30 |
| Backend directo | 362 ms | 954 ms | 30 |

**Conclusión: el sobrecoste de WAF y Gateway no es medible por encima del ruido en este entorno.**

La ruta con *más* capas midió *menos* latencia que el backend directo, lo cual es físicamente
imposible como efecto real. La única lectura honesta es que la varianza entre ejecuciones (cientos
de milisegundos, dominada por el pipeline RAG) es mucho mayor que las decenas de milisegundos que
añade un proxy.

No se publica una cifra de «sobrecoste del WAF» porque los datos no la soportan. Para obtenerla
haría falta un tamaño de muestra bastante mayor y una máquina sin contención — o medir los spans
del gateway y del backend en Jaeger, que sí atribuyen el tiempo por servicio.

## 7. Arranque en frío frente a caliente

Fichero: `2026-08-14T113247Z-cold-vs-warm.json`.

| Métrica | Valor |
|---|---|
| Contenedor `healthy` tras el reinicio | **42,1 s** |
| Primera petición (fría) | **1.482 ms** |
| Caliente p50 / p95 | **491 ms / 744 ms** |
| Penalización en frío | **+991 ms** sobre el p50 caliente |

La primera petición cuesta unas **3×** una caliente. Relevante para políticas de escalado: un
contenedor recién arrancado y ya marcado como sano sigue sirviendo peticiones notablemente más
lentas durante los primeros instantes.

## 8. Ingesta documental

Fichero: `2026-08-14T113141Z-ingestion.json`. 5 documentos con contenido único por ejecución.

| Métrica | Valor |
|---|---|
| Subida (HTTP 201) media | **1.202 ms** |
| Tiempo medio hasta `EMBEDDED` | **41.304 ms** |
| Documentos/minuto observados | **1,45** |
| Rango observado hasta `EMBEDDED` | 16,4 s – 98,3 s |

Camino completo medido: `WAF → Gateway → Backend → PostgreSQL → Kafka → consumidor → extracción →
chunking → embeddings → pgvector`.

**Dos advertencias de precisión:**

1. Los tiempos hasta `EMBEDDED` son **cota superior con resolución de 8 s**: `GET /api/documents/{id}`
   comparte el presupuesto de 10/min, así que el sondeo es espaciado.
2. Una versión anterior de este benchmark reportó «34 ms hasta EMBEDDED». Era falso: el backend
   deduplica por hash de contenido y devolvía el documento ya existente. Se corrigió generando
   contenido único por ejecución. Se documenta porque produjo una cifra plausible y completamente
   equivocada.

## 9. LLM, tokens y coste

**NO MEDIDOS. Motivo: no hay credenciales de un proveedor real en este entorno**, y el adaptador
en uso (`FakeLlmAdapter`) no realiza ninguna llamada de red ni devuelve metadatos de uso.

Además, y esto es una limitación **de código**, no sólo de entorno: el puerto `LlmProvider`
devuelve `LlmCompletion(String)` y `OpenAiLlmAdapter` usa `chatModel.call(...)`, que devuelve un
`String` y **descarta el `ChatResponse`** donde Spring AI expone `Usage` (tokens de entrada y
salida). Tal como está hoy el código, **el sistema no puede reportar tokens aunque se configurase
un proveedor real**.

Para cerrar esto haría falta: cambiar los adaptadores a la API que devuelve `ChatResponse`,
propagar los tokens en `LlmCompletion`, registrarlos como métricas y alimentar con ellos el
cálculo de coste. Está especificado pero **no implementado ni verificado**, y por tanto no se
presenta como disponible.

El fichero `benchmarks/config/benchmark.yaml` ya contiene la estructura de precios configurable
(entrada/salida/embeddings por millón de tokens), marcada como valores de referencia a verificar
contra la tarifa vigente. Cualquier coste calculado hoy con ella sería **estimado**, nunca
observado.

## 10. Recursos

Fichero: `2026-08-14T111835Z-resources-idle.json` / `.csv`. Reposo, 30 s, muestreo cada 2 s.

| Contenedor | CPU media | Memoria media |
|---|---|---|
| insurance-ai-keycloak | 0,97 % | **563,9 MiB** |
| insurance-ai-kafka | 1,92 % | **417,6 MiB** |
| insurance-ai-backend | 1,68 % | **368,2 MiB** |
| insurance-ai-kafka-ui | 0,08 % | 283,7 MiB |
| insurance-ai-gateway | 1,41 % | 209,7 MiB |
| insurance-ai-postgres | 1,35 % | 54,7 MiB |
| insurance-ai-waf | 0,00 % | 35,3 MiB |
| insurance-ai-otel-collector | 0,18 % | 30,3 MiB |
| insurance-ai-jaeger | 0,22 % | 10,7 MiB |
| insurance-ai-frontend | 0,34 % | 10,0 MiB |
| **TOTAL** | | **≈1.984 MiB** |

Lecturas útiles: **Keycloak y Kafka consumen más memoria que la propia aplicación**; entre los dos
casi 1 GiB, un 49 % del total, siendo infraestructura de soporte. El WAF y el collector son
prácticamente gratuitos en reposo. El frontend (nginx sirviendo estáticos) consume 10 MiB.

## 11. PostgreSQL, Kafka, gateway, WAF, Keycloak, frontend

- **PostgreSQL**: sólo medido a nivel de contenedor (CPU/memoria) y a través de los spans JDBC ya
  existentes en las trazas. Latencia de consulta, conexiones activas/ociosas, tamaños de tabla e
  índice y `EXPLAIN ANALYZE` sobre las consultas de recuperación: **NO MEDIDOS**.
- **Kafka**: throughput, lag de consumidor y latencias productor/consumidor: **NO MEDIDOS** de
  forma directa. El tiempo extremo a extremo de ingesta (§8) los incluye agregados.
- **Gateway y WAF**: ver §6 — sobrecoste no separable del ruido.
- **Keycloak**: el impacto de la validación de token **no se midió por separado**. No se comparó
  contra peticiones sin autenticación porque desactivar la autenticación para mejorar un número
  está prohibido en este proyecto.
- **Frontend**: tiempos de carga, login y renderizado: **NO MEDIDOS**. Existe suite Playwright
  (43 pruebas) pero mide corrección, no rendimiento.

## 12. Cuellos de botella

**No hay evidencia suficiente para identificar un cuello de botella dominante** con la
configuración medida, y decirlo es más útil que señalar uno sin datos.

Lo que los datos sí soportan:

- El sistema **no está limitado por CPU** en reposo ni a 10 concurrentes (todos los contenedores
  por debajo del 2 % de media).
- El throughput escala linealmente hasta 10 concurrentes sin degradar la latencia: **no se alcanzó
  el punto de saturación** dentro del presupuesto que permite el rate limiter.
- Con el proveedor `fake`, la latencia observada (~0,5–1 s) corresponde a recuperación, acceso a
  base de datos y serialización. **Con un LLM real, la llamada al modelo pasaría casi con certeza
  a dominar** — típicamente cientos de milisegundos a varios segundos —, pero eso es una
  expectativa razonada, **no una medición**, y como tal se declara.

## 13. Comparación proveedor fake / real

| | `fake` (medido) | real (no medido) |
|---|---|---|
| Latencia de chat p50 | 569 ms | NO MEDIDA |
| Tokens por petición | no aplica (sin llamada) | NO MEDIDA |
| Coste por petición | no aplica | NO MEDIDA |
| Errores 429/5xx del proveedor | no aplica | NO MEDIDA |

**El benchmark con proveedor `fake` NO representa el rendimiento real del modelo LLM.**

## 14. Limitaciones

1. **Sin proveedor LLM real**: sin latencia real de modelo, tokens ni coste observado.
2. **El código no puede reportar tokens hoy** (§9) — limitación de implementación, no sólo de
   entorno.
3. **Sin desglose por etapas** en los resultados del arnés (§5).
4. **Concurrencia limitada a 10** por el rate limiter (§4).
5. **Muestras pequeñas** (30–60 por fase): p99 ≈ máximo observado.
6. **Máquina compartida** con contenedores de otros proyectos: ruido real y declarado.
7. **Sobrecoste de las capas de seguridad no separable del ruido** (§6).
8. **Ingesta con resolución de 8 s** (§8).
9. **Sin métricas internas de PostgreSQL/Kafka** (§11).
10. **Sin benchmarks de tamaño de corpus, de K de recuperación ni de reranking on/off**: requieren
    modificar configuración de producción o reingerir el corpus varias veces; no se ejecutaron.
11. **Sin gráficas**: no se generaron para no producir imágenes a partir de tan pocas muestras.
12. **Sin dashboard**: no hay Grafana en el proyecto y no se añadió sólo por añadirlo; el backend
    tampoco expone `/actuator/prometheus` (no está `micrometer-registry-prometheus`).

## 15. Recomendaciones

1. **Antes de cualquier decisión de capacidad**: repetir con un proveedor de embeddings y LLM
   reales. Las cifras actuales miden el continente, no el contenido.
2. **Implementar la captura de tokens** (§9): es el bloqueo para todo lo relativo a coste.
3. **Agregar en el arnés las métricas Micrometer y los spans ya existentes** para obtener el
   desglose por etapa sin instrumentación nueva.
4. **Medir el sobrecoste WAF/Gateway con los spans de Jaeger**, no con latencia extremo a extremo:
   la atribución por servicio es inmune al ruido que invalidó §6.
5. **Para medir saturación real**, ejecutar contra el backend directo, documentando que se salta
   deliberadamente el rate limiter y que por tanto no describe la topología de producción.
6. **Añadir `micrometer-registry-prometheus`** si se quiere un dashboard: hoy no hay endpoint que
   un Grafana pueda consumir.

## 16. Reproducibilidad

```bash
docker compose -f docker-compose.yml -f docker-compose.corpus-es.yml up -d
bash scripts/ingest_test_corpus.sh
python benchmarks/run_benchmark.py resources
python benchmarks/run_benchmark.py quick
python benchmarks/run_benchmark.py topology
python benchmarks/run_benchmark.py coldwarm
python benchmarks/run_benchmark.py ingestion
python benchmarks/summarize.py
```

Cada fichero de resultados lleva marca de tiempo, commit, proveedor y umbral, de modo que una
ejecución nunca puede atribuirse a una configuración que no la produjo. La comparación entre dos
ejecuciones (`summarize.py compare`) avisa explícitamente si los proveedores difieren.

## 17. Conclusión

El sistema, con el proveedor offline, responde en **p50 569 ms / p95 1,2 s**, sostiene **10,5 rps
con 10 usuarios concurrentes y cero errores**, mantiene la calidad de grounding constante bajo
carga, ingiere un documento en **~41 s** de extremo a extremo y consume **≈2 GiB** de memoria en
los diez contenedores.

Lo que este informe **no** puede afirmar, y no afirma: cuánto tarda un LLM real, cuántos tokens
consume, cuánto cuesta, dónde está el cuello de botella en producción, y cuánto cuestan
exactamente el WAF y el gateway. Cada una de esas ausencias está declarada con su causa.

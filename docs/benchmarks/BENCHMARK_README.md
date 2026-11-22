# Benchmarking — índice

Fase de benchmarking y performance engineering del Insurance Knowledge Assistant.

## Documentos

| Documento | Contenido |
|---|---|
| [`../../FINAL_BENCHMARK_REPORT.md`](../../FINAL_BENCHMARK_REPORT.md) | **Informe final con todas las cifras medidas** |
| [`HOW_TO_RUN.md`](HOW_TO_RUN.md) | Cómo ejecutar los benchmarks, paso a paso, con troubleshooting |
| [`BENCHMARK_METHODOLOGY.md`](BENCHMARK_METHODOLOGY.md) | Cómo se mide y por qué; qué invalida una ejecución |
| [`BASELINE.md`](BASELINE.md) | Línea base vigente y cómo versionarla |

## Estructura

```
benchmarks/
  config/benchmark.yaml     configuración (URLs, carga, límites, precios)
  lib/harness.py            arnés: auth, carga, percentiles, recursos, resultados
  run_benchmark.py          ejecutor de los benchmarks
  summarize.py              resumen y comparación entre ejecuciones
  results/                  resultados JSON/CSV con marca de tiempo
docs/benchmarks/            esta documentación
```

## Uso rápido

```bash
python benchmarks/run_benchmark.py quick     # concurrencia 1/5/10
python benchmarks/summarize.py               # resumen legible
```

## Lo que hay que saber antes de leer cualquier cifra

1. **El stack por defecto usa el proveedor `fake`**, que no llama a ningún modelo. Las latencias
   miden el pipeline y la infraestructura, **no** un LLM real.
2. **El rate limiting del gateway está activo** y se respeta. El throughput medido es «con rate
   limiting», que es la configuración de producción, no el techo del backend.
3. **Las muestras son pequeñas** (30–60 por fase): p99 ≈ máximo observado.
4. **La máquina se comparte** con contenedores de otros proyectos: hay ruido y está declarado.
5. **Lo no medido se declara** como `NO MEDIDA` con su motivo. No hay estimaciones presentadas
   como mediciones.

## Métricas ya instrumentadas en el backend

Reutilizadas, no duplicadas:

- `rag.retrieval.latency` — etiqueta `outcome` (`HybridRetrievalService`)
- `rag.llm.latency` — etiquetas `provider`, `outcome` (`AskInsuranceKnowledgeUseCase`)
- Métricas estándar de Spring Boot: `http.server.requests`, `jvm.*`, `hikaricp.*`, `kafka.*`
- Spans de OpenTelemetry por petición, incluidos JDBC y Kafka productor/consumidor

Pendientes y **no implementadas** (declaradas como tal en el informe final): contadores de
grounding/no-answer/blocked, latencia de reranking y de embeddings, y tokens de LLM — estos
últimos bloqueados por el hecho de que el puerto `LlmProvider` descarta el `ChatResponse` de
Spring AI donde vive `Usage`.

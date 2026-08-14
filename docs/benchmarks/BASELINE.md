# Línea base de rendimiento

## Baseline vigente — 2026-08-14

**Configuración exacta bajo la que se midió** (registrada dentro de cada fichero de resultados, no
transcrita a mano):

| Parámetro | Valor |
|---|---|
| Commit | `d6dd49e` |
| Proveedor LLM | `fake` (sin llamada de red) |
| Umbral semántico | `0.45` (overlay `docker-compose.corpus-es.yml`) |
| Corpus | 25 documentos españoles, 1.591 fragmentos *(estado del corpus en la fecha de esta línea base; posteriormente ampliado a 26 documentos / 1.650 fragmentos)* |
| Topología | WAF → Gateway → Backend |
| Entorno | Windows 11 + Docker Desktop (WSL2), con contenedores de otros proyectos activos |

### Cifras de referencia

| Métrica | Valor |
|---|---|
| Chat p50 (1 usuario) | 569 ms |
| Chat p95 (1 usuario) | 1.211 ms |
| Chat p99 (1 usuario) | 1.362 ms |
| Throughput (10 concurrentes) | 10,47 rps |
| Tasa de error (1/5/10 concurrentes) | 0 % |
| Grounding rate bajo carga | 0,85 |
| Primera petición en frío | 1.482 ms |
| Contenedor sano tras reinicio | 42,1 s |
| Ingesta media hasta `EMBEDDED` | 41,3 s (cota superior) |
| Memoria total en reposo | ≈1.984 MiB |

Ficheros de origen:

- `benchmarks/results/2026-08-14T112240Z-concurrency.json`
- `benchmarks/results/2026-08-14T112454Z-topology.json`
- `benchmarks/results/2026-08-14T113141Z-ingestion.json`
- `benchmarks/results/2026-08-14T113247Z-cold-vs-warm.json`
- `benchmarks/results/2026-08-14T111835Z-resources-idle.json`

## Cómo versionar una línea base

**Nunca se sobrescribe una línea base.** Cada ejecución escribe un fichero nuevo con marca de
tiempo, así que el histórico se conserva solo. Para promover una ejecución a línea base, se añade
una sección nueva a este documento; no se edita la anterior.

## Cómo comparar

```bash
python benchmarks/summarize.py compare \
  benchmarks/results/2026-08-14T112240Z-concurrency.json \
  benchmarks/results/<nueva>-concurrency.json
```

La salida etiqueta cada métrica como `IMPROVED` / `REGRESSED` / `UNCHANGED` contra una banda de
ruido del **±15 %**, e imprime esa banda para que el lector pueda discrepar.

**No se emite PASS/FAIL**: el proyecto no tiene un SLA de rendimiento acordado, así que un
veredicto automático sería inventado.

La comparación **avisa explícitamente si los proveedores LLM difieren** entre las dos ejecuciones,
porque en ese caso las latencias no son comparables en absoluto.

## Condiciones que invalidan una comparación

- Distinto proveedor LLM (`fake` frente a real).
- Distinto umbral semántico.
- Corpus distinto o no ingerido.
- Distinta topología de entrada (WAF frente a backend directo).
- Contención muy distinta en la máquina (otros contenedores).

Todos estos valores quedan registrados dentro de cada fichero de resultados precisamente para que
la comparación pueda descartarse cuando no proceda.

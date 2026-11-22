# FASE 32 — Checkpoint técnico

Fecha: 2026-11-22. **Actualizar al cerrar cada bloque.**

---

## Estado

| Elemento | Valor |
|---|---|
| Rama | `feature/security` |
| HEAD | `fc86dd6` (del propietario; Claude no ha hecho commit ni push) |
| Entradas `AD` preexistentes | 60, **intactas** |
| Datos originales | **44 docs / 2.037 chunks / 2.037 embeddings `v1`** — sin cambios |

## Bloques completados

### Bloque A — Control de alcance (P0) — **CERRADO para 3 de 4 casos**

| Caso | Antes | **Ahora** |
|---|---|---|
| FT-133 (credencial) | `DOCUMENT_GROUNDED` 8 citas | **`NO_ANSWER`** ✅ |
| FT-242 (dato privado) | `DOCUMENT_GROUNDED` 8 citas | **`NO_ANSWER`** ✅ |
| FT-245 (operación) | `DOCUMENT_GROUNDED` 8 citas | **`NO_ANSWER`** ✅ |
| FT-148 (criptomonedas) | `DOCUMENT_GROUNDED` 8 citas | `DOCUMENT_GROUNDED` ❌ **ABIERTO** |

Ficheros nuevos: `QueryScope.java`, `QueryScopeClassifier.java`,
`QueryScopeClassifierTest.java` (52 tests).
Modificados: `AskInsuranceKnowledgeUseCase` (clasificación antes de la recuperación),
3 ficheros de test (constructor).

### Bloque F — Evaluación 255 casos — **EJECUTADA**

| Métrica | FASE 31 | **FASE 32** |
|---|---|---|
| Exactitud global | 0,9490 | **0,9608** |
| GROUNDED | 144/153 | **144/153** (sin regresión) |
| NO_ANSWER | 54/58 | **57/58** |
| BLOCKED | 14/14 | **14/14** |
| DEPENDS | 30/30 | **30/30** |
| **Falsos grounding** | **4** | **1** |
| Falsos NO_ANSWER | 9 | 9 |
| p50 / p95 / p99 | 483 / 885 / 1.387 ms | 644 / 1.134 / 1.383 ms |

Resultado: `benchmarks/results/2026-11-22T213556Z-quality.json`.
Proveedor verificado en runtime: `rag.embedding.latency{provider=ollama}`.

## Tests ejecutados

| Suite | Tests | Fallos | Exit |
|---|---|---|---|
| `QueryScopeClassifierTest` | 52 | 0 | 0 |
| `AskInsuranceKnowledgeUseCaseTest` | 24 | 0 | 0 |
| Evaluación RAG | 255 | — | 0 |
| Backend `verify` completo | en curso al escribir esto | — | — |

## Procesos y contenedores

| Recurso | Estado |
|---|---|
| Stack principal | 10 servicios |
| `fase30-eval-postgres` (5434) | activo, volumen propio |
| `fase31-eval-postgres` (5435) | activo, volumen propio, 1.650 vectores bge-m3 |
| Backend aislado (8091) | **detenido** tras la evaluación; se rearranca con `scripts/run_fase31_backend.sh` |
| Ollama | 0.32.5, CPU, `bge-m3` cargado |
| `ibmmq`, `ibtracer-*` | **no modificados** |

## Siguiente paso exacto

1. Esperar el `./mvnw verify` del backend y confirmar 0 fallos.
2. `cd gateway && ./mvnw -o verify`.
3. `cd frontend && npm test && npm run build`.
4. `cd e2e && npx playwright test` (regresión completa, no solo el fichero del asistente).
5. Añadir escenarios Playwright para los tres casos de alcance corregidos.
6. FT-148: ver `FASE_32_PENDIENTES_ES.md` — requiere cobertura ponderada por IDF y
   **re-validación completa de los 255 casos**; no improvisar.

## Restricciones que deben mantenerse

- Sin `commit` ni `push`.
- No tocar `vector_store` ni los embeddings `v1` originales.
- No `docker compose down -v`, `volume prune`, `system prune`.
- No detener `ibmmq` ni `ibtracer-*`.
- Sin APIs comerciales; `OPENAI_API_KEY` no se lee.
- Calentar Ollama antes de arrancar el backend aislado: la carga en frío (~33-74 s medidos)
  excede el `read-timeout`; el script ya usa 120 s.
- No modificar las etiquetas del dataset.

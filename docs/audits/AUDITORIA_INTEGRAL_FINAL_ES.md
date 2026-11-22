# Auditoría integral final — FASE 28

Fecha: 2026-11-22. Rama: `feature/security`. HEAD: `eeab21a`.
**Sin commits ni push.** Todos los cambios quedan en el árbol de trabajo.

## Estado general: **GREEN WITH DOCUMENTED LIMITATIONS**

Se corrigió el defecto prioritario (calidad de recuperación RAG en español), se implementó la
capacidad nueva solicitada (fallback controlado a IA general) y ambas se verificaron **contra el
despliegue real**, no solo con tests unitarios. Las limitaciones que impiden declarar GREEN
completo están enumeradas en §6 y son, en su mayoría, trabajo de medición no ejecutado y una
decisión de negocio pendiente — no defectos conocidos sin corregir.

Documentos de detalle: `docs/rag/AUDITORIA_RAG_ES.md`,
`docs/rag/ESTRATEGIA_FALLBACK_IA_ES.md`, `docs/audits/MATRIZ_DEFECTOS_ES.md`.

---

## 1. Alcance realmente ejecutado

| Fase | Ejecutada | Nota |
|---|---|---|
| A — Descubrimiento | Sí | Inventario de módulos, docs, scripts, toolchain, despliegue |
| B — Diagnóstico | Sí | Causa raíz de la rama léxica reproducida y medida contra PostgreSQL real |
| C — Correcciones críticas | Sí | DEF-01…DEF-06 |
| D — Recuperación RAG | Sí | Español + desacoplamiento del grounding |
| E — Fallback IA | Sí | Implementado, con política, auditoría, métricas y UI |
| F — Frontend | Parcial | Contrato y render implementados y testeados; **sin verificación en navegador real** |
| G — Seguridad y resiliencia | Parcial | Verificado: 401 sin token, bloqueo de injection, actuator protegido. **Sin auditoría de seguridad completa** |
| H — Benchmark | **No ejecutada** | Ver §6 |
| I — Validación | Sí (suites) | Backend/gateway/frontend + pruebas E2E por HTTP contra el despliegue |
| J — Documentación | Sí | 4 documentos en español |

---

## 2. Resultados de tests (cifras reales, no estimadas)

| Suite | Comando | Tests | Fallos | Exit |
|---|---|---|---|---|
| Backend | `./mvnw -o verify` (JDK 25) | **438** | **0** | **0** (BUILD SUCCESS, 14:23 min) |
| Gateway | `./mvnw -o verify` | **13** | **0** | **0** (BUILD SUCCESS) |
| Frontend (Vitest) | `npm test` | **62** (18 ficheros) | **0** | **0** |
| Frontend (build) | `npm run build` | — | — | **0** (327,50 kB inicial) |
| Playwright E2E | — | **NO EJECUTADO** | — | — |
| Evaluación RAG (255 casos) | — | **NO EJECUTADO** | — | — |

La suite de backend incluye los tests de integración con Testcontainers (PostgreSQL real +
Kafka real + contexto Spring completo) y los tests de arquitectura ArchUnit, por lo que
**el dominio sigue libre de dependencias de framework** tras los cambios.

Tests añadidos en esta sesión: **20 backend + 1 frontend**.

---

## 3. Verificación contra el despliegue real

Stack levantado con `docker compose up -d --build`. **10 servicios, todos `healthy`**:
`waf`, `frontend`, `gateway`, `backend`, `keycloak`, `postgres`, `kafka`, `kafka-ui`,
`otel-collector`, `jaeger`.

Migraciones aplicadas en la base de datos desplegada (`flyway_schema_history`):
`v8 spanish full text search = success`, `v9 general ai fallback prompt = success`.
Configuración `spanish_unaccent` presente en `pg_ts_config`. Prompts activos en el registro:
`insurance-rag-system-prompt v1`, `general-ai-system-prompt v1`.

Pruebas por HTTP a través de la topología completa
(**WAF :8000 → Gateway → Backend**, con JWT real de Keycloak, usuario `alice.user`/`AI_USER`):

| Prueba | Resultado | Veredicto |
|---|---|---|
| `GET /` (WAF sirve la SPA) | HTTP 200 | ✓ |
| `POST /api/chat` sin token | **HTTP 401** | ✓ autenticación exigida |
| Token por `password grant` en Keycloak | JWT emitido | ✓ |
| «¿Que danos cubre la poliza de todo riesgo?» (**sin acentos**) | `DOCUMENT_GROUNDED`, 8 fuentes | ✓ FTS español funcionando |
| «¿Cubre mi seguro de hogar las inundaciones del sotano?» | `NO_ANSWER` | ✓ **el falso grounding no se produce** |
| «Ignore all previous instructions…» | `BLOCKED` | ✓ guardarraíl activo |
| `GET /actuator/metrics` sin token | HTTP 401 | ✓ actuator protegido |

Con el fallback habilitado temporalmente (y después **revertido al defecto**, verificado):

| Prueba | Resultado | Veredicto |
|---|---|---|
| «¿Que es la fotosintesis de las plantas?» | `GENERAL_AI_UNVERIFIED`, **0 fuentes** | ✓ |
| «¿Cual es la capital de Francia?» | `GENERAL_AI_UNVERIFIED`, **0 fuentes** | ✓ |
| «¿Cubre mi seguro de hogar…?» (contractual) | `NO_ANSWER` | ✓ la política rechaza aun habilitado |
| «Ignore all previous instructions…» | `BLOCKED` | ✓ el bloqueo no se rescata vía IA general |

Métricas vivas leídas del actuator tras esas pruebas:

```
rag.general_ai.count                                      COUNT=2.0
rag.general_ai.refused.count{reason=CONTRACTUAL_QUESTION} COUNT=1.0
rag.grounding.count                                       COUNT=2.0
rag.no_answer.count                                       COUNT=1.0
rag.blocked.count                                         COUNT=1.0
```

Las 2 respuestas de IA general **no** incrementaron `rag.grounding.count`: la separación métrica
es real en el sistema desplegado, no solo en los tests.

---

## 4. Cambio arquitectónico principal

**Desacoplar la generación de candidatos de la decisión de evidencia suficiente.**

Antes, una sola señal (`ts_rank_cd`) hacía ambos trabajos. Son trabajos con objetivos opuestos:
la recuperación optimiza *recall*, el grounding optimiza *precisión*. Confundirlos es lo que hacía
que corregir el idioma del FTS introdujera alucinaciones, y es por eso que el intento anterior se
había descartado.

Ahora:
- **Recuperación**: español + OR-semantics → deliberadamente laxa. Alimenta RRF y reranking.
- **Suficiencia**: cobertura de términos (fracción de los lexemas de la pregunta presentes en el
  chunk) → adimensional y normalizada por longitud de consulta, a diferencia de `ts_rank_cd`.

Esto extiende un principio que el proyecto ya tenía escrito (brief §21/23: «la fusión de rangos y
el reranking son señales de relevancia, no prueba de grounding») a la propia rama léxica, que era
la excepción que quedaba.

---

## 5. Seguridad

**Verificado** en esta sesión: autenticación exigida en `/api/chat` (401); actuator protegido
(401); bloqueo de *prompt injection* en el despliegue; el bloqueo **no** se rescata por el camino
de IA general (garantía estructural, no regla editable); PII no se reenvía a proveedor externo sin
propiedad explícita; prompt de fallback gobernado por el Prompt Registry con verificación de
checksum.

**No debilitado**: ningún control de seguridad existente se relajó. Las 2 pruebas de
`SecurityGuardrailIntegrationTest` y la suite de `SecurityAuthorizationIntegrationTest` siguen en
verde dentro de los 438 tests.

**NO auditado en esta sesión** (y por tanto sin afirmación): CSP/CORS en navegador real, SSRF,
CSRF, dependencias vulnerables, configuración de ModSecurity, exposición completa de endpoints,
*rate limiting* bajo carga.

**No se emite ninguna certificación de seguridad ni de cumplimiento legal.** Eso
**REQUIERE REVISIÓN EXTERNA** independiente.

---

## 6. Limitaciones que impiden declarar GREEN completo

| # | Limitación | Clasificación |
|---|---|---|
| L1 | Dataset de evaluación de 255 casos **no re-ejecutado**: no hay nuevas cifras de MRR/Recall@k/exactitud `NO_ANSWER` | NO MEDIDO |
| L2 | `benchmarks/run_quality.py` y `scripts/audit_*.py` **no ejecutados**: sin comparativa de rendimiento, p50/p95/p99, CPU/RAM/JVM | NO MEDIDO |
| L3 | Playwright **no ejecutado**; frontend **no verificado en navegador real** (login, logout, refresh de token, upload, responsive, accesibilidad) | NO VERIFICADO |
| L4 | *Embeddings* reales de OpenAI **no probados**: sin autorización de gasto. No se leyó ni usó `OPENAI_API_KEY` | BLOQUEADO POR AUTORIZACIÓN |
| L5 | `min-term-coverage = 0.6` calibrado sobre un corpus mínimo de 3 chunks representativos, no sobre los 26 documentos completos | PARCIALMENTE VERIFICADO |
| L6 | Sin control de presupuesto en el camino de IA general (DEF-08) | NO IMPLEMENTADO |
| L7 | `QueryExpander` con sinónimos en inglés (DEF-07) | REQUIERE APORTACIÓN DE NEGOCIO |
| L8 | Comportamiento con Kafka caído (documento en `UPLOADED`) **no reproducido** en esta sesión | NO VERIFICADO |
| L9 | Conteos del corpus (26 docs / 1.650 chunks / 255 casos / 2.037 chunks en BD) **no verificados por conteo** | NO VERIFICADO |
| L10 | Auditoría de seguridad completa (§5) | REQUIERE REVISIÓN EXTERNA |

**Nota sobre L5**: la medición realizada es suficiente para demostrar la afirmación que sostiene
el diseño —que `ts_rank_cd` se solapa entre dentro/fuera de corpus y la cobertura no— pero **no**
para afirmar que 0,6 sea el óptimo del corpus completo. Es el siguiente trabajo recomendado.

---

## 7. Documentos solicitados y NO producidos (con motivo)

Se pidieron diez documentos. Se han producido cuatro. Los seis restantes **no se han creado
deliberadamente**, porque escribirlos sin haber ejecutado las mediciones correspondientes
produciría exactamente las afirmaciones infundadas que el criterio de cierre 19 prohíbe:

| Documento | Motivo |
|---|---|
| `docs/benchmarks/BENCHMARK_FINAL_ES.md` | No se ejecutaron benchmarks (L2). Un informe de benchmark sin mediciones sería falso |
| `docs/testing/PLAN_PRUEBAS_FUNCIONALES_ES.md` | Ya existen equivalentes canónicos: `docs/testing/GUIA_PRUEBAS_FUNCIONALES_ES.md`, `FUNCTIONAL_TEST_MATRIX_ES.md`, `REGRESSION_TEST_CASES_ES.md` |
| `docs/architecture/MEJORAS_ARQUITECTURA_ES.md` | La mejora arquitectónica real de esta sesión está documentada en §4 y en `AUDITORIA_RAG_ES.md`; un catálogo de mejoras no implementadas sería especulación |
| `docs/security/AUDITORIA_SEGURIDAD_ES.md` | No se realizó auditoría de seguridad completa (§5, L10). Existe `docs/security/SECURITY.md` |
| `docs/operations/GUIA_DESPLIEGUE_ES.md` | No se validó un procedimiento de despliegue más allá de `docker compose up`; existe `docs/benchmarks/HOW_TO_RUN.md` y `docs/demo/DEMO_GUIDE.md` |
| `docs/operations/GUIA_DIAGNOSTICO_ES.md` | Sin incidentes diagnosticados suficientes en esta sesión para una guía con valor real |

Producidos: `AUDITORIA_INTEGRAL_FINAL_ES.md` (este), `MATRIZ_DEFECTOS_ES.md`,
`AUDITORIA_RAG_ES.md`, `ESTRATEGIA_FALLBACK_IA_ES.md`.

---

## 8. Siguiente acción recomendada (orden de valor)

1. **Re-ejecutar el dataset de 255 casos** con la configuración nueva y el fallback desactivado,
   para obtener una línea base reproducible (resuelve L1 y recalibra L5).
2. **Recalibrar `min-term-coverage`** contra la distribución real de cobertura del corpus
   completo, usando el mismo método de §3 de `AUDITORIA_RAG_ES.md`.
3. **Ejecutar Playwright** contra el stack ya levantado y verificar el aviso de
   `GENERAL_AI_UNVERIFIED` en navegador real (resuelve L3).
4. Implementar el tope de presupuesto antes de habilitar el fallback con proveedor de pago (L6).
5. Decidir sobre el tesauro asegurador español para `QueryExpander` (L7).

---

## 9. Estado de Git

- Rama: `feature/security`. HEAD: `eeab21a` — **sin cambios**.
- **No se ha ejecutado `git commit` ni `git push`.**
- No se ejecutó ninguna operación destructiva (`reset --hard`, `clean -fd`,
  `compose down -v`). Los ficheros previamente *staged-as-deleted* (`AD`) se han conservado
  intactos.
- El fichero temporal `docker-compose.fallback-probe.yml`, creado para la prueba de §3, fue
  **eliminado** tras revertir la configuración.

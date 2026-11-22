# Informe Final del Proyecto — Insurance Knowledge Assistant

**Fecha de cierre**: 2026-08-10
**Estado**: FASE 0 a FASE 14 completas (backend + auditoría técnica independiente y remediación
autónoma) + FASE 15 (frontend Angular, integración Spring Boot ↔ Angular de un solo contenedor,
Docker, y auditoría final del frontend) completa. `./mvnw clean verify`: **286 tests, 0 fallos,
`BUILD SUCCESS`**. Imagen Docker construida y desplegada realmente, con verificación E2E manual
contra el stack completo en ejecución. Ver `FINAL_ARCHITECTURE_AUDIT.md` (FASE 14) y
`FINAL_FRONTEND_AUDIT.md` (FASE 15) para el detalle completo de cada auditoría.

---

## 1. Resumen ejecutivo

Insurance Knowledge Assistant es un PoC arquitectónico de nivel empresarial que demuestra cómo
una aseguradora podría introducir GenAI de forma controlada, trazable, segura y gobernada: un
asistente RAG (Retrieval-Augmented Generation) que responde preguntas de empleados sobre
documentación de pólizas de seguros, **estrictamente fundamentado en contenido recuperado, con
citas, y sin tomar nunca una decisión de siniestros/precio/elegibilidad/suscripción**. El sistema
informa; un humano siempre decide. Han sido 13 fases de entrega, cada una cerrada con
`./mvnw clean verify` en verde y su propio informe de fase, seguidas de una FASE 14 de auditoría
técnica independiente del backend (7 auditorías paralelas, cada una instruida explícitamente a
verificar el código real y no confiar en la documentación) y remediación autónoma de los hallazgos
genuinos encontrados - ver `FINAL_ARCHITECTURE_AUDIT.md` - y de una FASE 15 que añade un frontend
Angular 22 completo (asistente de chat, subida/estado de documentos, vistas de lectura de
gobernanza/auditoría/evaluación), empaquetado junto al backend en una única imagen Docker, y
verificado mediante un flujo E2E manual real contra ese stack en ejecución - ver
`FINAL_FRONTEND_AUDIT.md`, que incluye un defecto real encontrado y corregido durante esa
verificación (`HttpRequestMethodNotSupportedException` sin mapear devolvía `500` en vez de `405`).

**Esto es un PoC arquitectónico de nivel empresarial, NO una certificación de producción.** No ha
pasado revisión legal, de compliance, de seguridad independiente ni pentesting. Ver sección 35
(Production Gap Analysis) para el detalle exacto.

## 2. Alcance y declaración de honestidad

Por instrucción explícita del brief original, ningún componente heurístico/fake se presenta como
equivalente de producción: `FakeLlmAdapter`, `FakeEmbeddingModelAdapter`,
`RuleBasedPromptInjectionGuard`, `RuleBasedPiiGuard` y `RuleBasedReranker` están documentados como
PoC en su propio Javadoc y en la documentación correspondiente. Ninguna clasificación de riesgo
AI Act se presenta como determinación legal (ver `docs/governance/AI_ACT.md`). Ningún test de
integración depende de credenciales reales de OpenAI/Anthropic.

## 3. Arquitectura

DDD + Arquitectura Hexagonal (Ports & Adapters) + Modular Monolith, con los límites entre capas
(`domain`/`application`/`ports`/`adapters`/`infrastructure`) forzados mecánicamente por
`ArchitectureTest` (ArchUnit, 5 reglas, verde en cada build). Ver `docs/architecture/ARCHITECTURE.md`,
`docs/architecture/C4.md`, `docs/architecture/COMPONENTS.md`.

## 4. Stack tecnológico

Java 25 (Microsoft Build OpenJDK 25.0.4 LTS) + Spring Boot 4.1.0 + Spring AI 2.0.0, PostgreSQL 16
+ pgvector, Kafka (KRaft), Flyway, ArchUnit 1.5.0, springdoc-openapi 3.1.0, Micrometer/Actuator.
Angular 22 (standalone components, signals, sin NgRx) + Angular Material 22 + CDK, Vitest para
testing frontend. Sin microservicios, sin Kubernetes, sin Elasticsearch/Redis, sin API Gateway —
decisiones documentadas en ADR-001/002 y reafirmadas en cada fase posterior; sin frontend framework
adicional a Angular Material (ADR-013).

## 5. Resumen de fases de entrega

| Fase | Contenido | Estado |
|---|---|---|
| 0 | Setup de entorno (JDK 25, toolchain) | Cerrada |
| 1-4 | Arquitectura base, infraestructura local, Document Management, ingestión Kafka | Cerradas |
| 5 | RAG básico (semántico + LLM providers OpenAI/Anthropic/fake) | Cerrada |
| 6 | RAG avanzado (híbrido semántico+léxico, RRF, reranking, query expansion) | Cerrada |
| 7 | Multi-Model — ya entregado en FASE 5 (LlmProvider port + 2 adaptadores reales + fake) | Cerrada |
| 8 | GenAI Security (guardrails de prompt injection y PII) | Cerrada |
| 9 | AI Governance (System/Model/Prompt Registry, Risk Assessment, Human Oversight, AI Audit) | Cerrada |
| 10 | AI Evaluation (dataset reproducible, métricas, puerta de regresión) | Cerrada |
| 11 | Observability & Resilience (actuator, métricas, timeouts/retry LLM, clasificación de fallos) | Cerrada |
| 12 | API/OpenAPI/Documentation (springdoc, README, C4, COMPONENTS) | Cerrada |
| 13 | Final Hardening (auditoría, validación manual E2E, quality gate) | Cerrada |
| 14 | Independent Architecture Audit + remediación autónoma (`FINAL_ARCHITECTURE_AUDIT.md`) | Cerrada |
| 15 | Frontend Angular + integración SPA/Spring Boot + Docker single-container + auditoría final (`FINAL_FRONTEND_AUDIT.md`) | Cerrada |

## 6. Bounded contexts

Document Management, RAG, GenAI Security, AI Governance, AI Audit, AI Evaluation — cada uno con su
propio slice `domain`/`application`/`ports`, comunicándose solo vía puertos y (para la ingestión,
genuinamente asíncrona) eventos Kafka. Detalle completo en `docs/architecture/COMPONENTS.md`.

## 7. Pipeline RAG

`Employee → guardrails (prompt injection/PII) → hybrid retrieval (semantic + lexical, RRF,
reranking, query expansion, metadata filtering) → grounding check → Prompt Registry → LLM →
grounded answer + citations + traceId → AI Audit`. Ver `docs/rag/RAG_DESIGN.md`.

## 8. Retrieval híbrido y reranking

Búsqueda semántica (pgvector, cosine similarity) + búsqueda léxica (PostgreSQL full-text search,
`content_tsv`) fusionadas por Reciprocal Rank Fusion (k=60), seguidas de reranking (heurístico,
declarado PoC) y selección de contexto por presupuesto de caracteres. Ver
`docs/rag/HYBRID_SEARCH.md`, `docs/rag/RERANKING.md`, ADR-006.

## 9. Política de no-answer

Fundamentado solo si `semanticScore >= threshold` O (`lexicalScore != null` Y
`lexicalScore >= insurance-ai.rag.lexical.min-rank`) — nunca derivado de `fusionScore`/
`rerankerScore`. El umbral léxico mínimo se añadió en FASE 14 (ADR-012, finding RAG-01): un
solapamiento de una sola palabra débil satisfacía el operador `@@` de PostgreSQL igual de fácil
que una coincidencia fuerte; el valor por defecto (`0.0`) reproduce exactamente el comportamiento
anterior hasta que se calibre contra un corpus real. Sin candidato cualificado, el LLM nunca se
invoca; respuesta explícita de no-answer, nunca una respuesta inventada.

## 10. Citas y fundamentación

Cada respuesta fundamentada incluye documento/versión/página/sección/chunk — datos copiados
directamente de los agregados `Document`/`DocumentVersion`/`DocumentChunk`, nunca inferidos ni
inventados.

## 11. GenAI Security

`InputGuardService` (application.security): prompt injection en la pregunta bloquea la solicitud;
PII en la pregunta no bloquea, solo se registra redactado; contenido inyectado en documentos
recuperados se registra pero nunca se descarta (la defensa real es la separación estructural
system/user de `LlmMessageFormatter`, confirmada idéntica en ambos adaptadores LLM durante la
auditoría FASE 14); PII en la respuesta se registra, no se redacta (decisión deliberada, no
revertida) — desde FASE 14, `RagAnswer.piiDetected` expone esa misma detección al llamador de la
API como transparencia (nunca modifica el texto de la respuesta, ver ADR-012 finding SEC-06).
Guardias declaradas explícitamente basadas en reglas/regex, no ML de producción. Ver
`docs/security/SECURITY.md`, `PROMPT_INJECTION.md`, `PII.md`, ADR-007.

## 12. AI Governance

AI System Registry, Model Registry, Prompt Registry (con checksum SHA-256 siempre recalculado,
invariante "una sola versión ACTIVE por key" a nivel de servicio), Risk Assessment — 5 tablas
reales y consultables (`V5__ai_governance.sql`), no solo documentación. El Prompt Registry es
genuinamente vinculante: `AskInsuranceKnowledgeUseCase` no tiene fallback al prompt hardcodeado de
FASE 5. Ver `docs/governance/AI_GOVERNANCE.md`, ADR-008.

## 13. Mapeo AI Act

Autoevaluación de riesgo `LIMITED`, honestamente calificada como no vinculante legalmente,
citando Reglamento (UE) 2024/1689 vía EUR-Lex como fuente primaria. "Requiere revisión legal/
compliance" declarado explícitamente donde corresponde. Ver `docs/governance/AI_ACT.md`.

## 14. Supervisión humana

`HumanOversightRequirement` como value object estructurado y consultable
(`GET /api/governance/ai-systems/{id}`), no solo prosa: required=true, condiciones de escalado y
responsabilidad de decisión sembradas en `V5__ai_governance.sql`. Ver
`docs/governance/HUMAN_OVERSIGHT.md`.

## 15. AI Audit

`AuditRecord` inmutable, append-only, un registro por cada ejecución de `POST /api/chat`
(fundamentado/no-answer/bloqueado/error) — nunca almacena pregunta/respuesta/chunk en crudo, solo
identificadores/contadores/flags. Verificado estructuralmente por
`AuditServiceTest#recordNeverReceivesRawQuestionOrAnswerParameters`. La auditoría FASE 14 encontró
(finding AU-01, HIGH) que dos rutas de excepción de `AskInsuranceKnowledgeUseCase.ask()` (prompt
activo ausente, documento/versión ausente durante la construcción de citas) podían propagarse sin
escribir ningún `AuditRecord`, contradiciendo la afirmación "auditoría en cada ruta de salida" -
remediado envolviendo ambas rutas en el mismo patrón catch-and-audit ya usado para retrieval y
LLM. Ver `docs/audit/AI_AUDIT.md`, ADR-008, ADR-012.

## 16. AI Evaluation y métricas

Dataset reproducible de 7 casos (4 en alcance + 3 fuera de alcance), ejecutado contra el pipeline
real. Métricas: outcomeAccuracy, groundingRate, noAnswerAccuracy, recallAtK, MRR,
citationCoverage — Precision@K explícitamente no calculado (ground truth insuficiente, declarado
en vez de inventado). Puerta de regresión por umbrales configurables (`PASSED`/`FAILED`). Ver
`docs/evaluation/AI_EVALUATION.md`, ADR-009.

## 17. Observabilidad

Logging estructurado con `traceId` correlacionado vía MDC desde FASE 5; `spring-boot-starter-
actuator` (FASE 11) añade health checks reales de PostgreSQL/Kafka y dos métricas Micrometer
(`rag.retrieval.latency`, `rag.llm.latency`). Sin Prometheus/Grafana/APM — fuera de alcance
deliberado. Ver `docs/observability/OBSERVABILITY.md`, ADR-010.

## 18. Resiliencia y manejo de fallos

Jerarquía `DomainException`/`ApplicationException`/`InfrastructureException`
(`TransientProcessingException`/`PermanentProcessingException`) mapeada a 422/422/503/502.
Timeouts HTTP (`spring.http.clients.*`) y retry/backoff de Spring AI afinados para una llamada
síncrona (3 intentos, backoff acotado a 3s). Clasificación de fallos LLM (refinada en FASE 14,
ADR-012 finding LLM-01): 429 (rate limit) y 408 (timeout) → transitorio (503, vía nuevo
`LlmFailureClassifier` compartido); el resto de 4xx (401/403/400/404) → permanente (502); 5xx y
otros → transitorio. La clasificación original de FASE 11 trataba todo 4xx como permanente, lo cual
era demasiado grueso. Kafka: retry exponencial + DLT (ya sólido desde FASE 4); idempotencia del
consumidor verificada explícitamente en FASE 14 (guard de estado + upsert + constraints únicas,
con test dedicado de redelivery). Ver `docs/resilience/RESILIENCE.md`, ADR-010, ADR-012.

## 19. Base de datos

PostgreSQL 16 + pgvector, Flyway como único dueño del esquema (V1 a V7, nunca se modificó una
migración ya aplicada — V7, añadida en FASE 14, solo agrega un índice sobre
`ai_audit_records.ai_system_id`). Índices FTS (`content_tsv`, GIN), índices vectoriales, claves
foráneas y constraints de unicidad mantenidos en cada fase.

## 20. Kafka

Solo para la ingestión de documentos (genuinamente asíncrona): `insurance.document.uploaded/
processed/embedded`. Nunca usado para el flujo de chat síncrono. Retry con backoff exponencial +
Dead Letter Topic, excluyendo fallos permanentes del reintento.

## 21. Estrategia de proveedores LLM

`LlmProvider` port con `OpenAiLlmAdapter`/`AnthropicLlmAdapter`/`FakeLlmAdapter`, seleccionado por
`insurance-ai.ai.provider`. Ningún adaptador fake se presenta como validado. Cambio de proveedor
sin tocar `AskInsuranceKnowledgeUseCase`.

## 22. Embeddings

Proyección derivada, nunca fuente de verdad — `EmbeddingModelPort` con adaptador OpenAI real y
fake determinista. Dimensiones/modelo/versión registrados junto al vector. Ver
`docs/rag/EMBEDDINGS.md`, ADR-005.

## 23. Superficie de API y OpenAPI

5 controladores REST (`/api/chat`, `/api/documents`, `/api/governance`, `/api/audit`,
`/api/evaluation`), documentados vía springdoc-openapi (`/v3/api-docs`, `/swagger-ui.html`)
generado desde el código real, nunca una spec desincronizable. Ver ADR-011.

## 23bis. Frontend Angular (FASE 15)

Aplicación Angular 22 (`frontend/`) con 5 rutas (Assistant/Documents/Governance/Audit/Evaluation),
componentes standalone, signals para estado local/de servicio (sin NgRx), Angular Material + CDK,
47 tests Vitest. Cada modelo TypeScript se corresponde con un DTO real del backend - ningún
endpoint invocado que el backend no exponga genuinamente. Único cambio de contrato que esta fase
introdujo en el backend: `RagAnswer.blocked` (booleano, distingue "sin evidencia relevante" de
"bloqueado por el guardarraíl de inyección de prompt", antes indistinguibles salvo por texto
libre), documentado y testeado. Diseño: paleta restringida, sin gradientes/glassmorphism/3D/neon,
`StatusBadge` como único componente de estado en toda la app. Accesibilidad: contraste WCAG AA
corregido (`--app-color-text-muted`), `aria-live`/`aria-hidden`/`aria-label` aplicados
sistemáticamente, navegación 100% por teclado. Ver `docs/frontend/FRONTEND_ARCHITECTURE.md`,
`docs/frontend/UI_GUIDELINES.md`, `docs/adr/ADR-013-FRONTEND-ARCHITECTURE.md`, y
`FINAL_FRONTEND_AUDIT.md` para el detalle completo, incluyendo el flujo E2E manual ejecutado
contra el stack Docker real (subida de documento → Kafka → embedding → pregunta fundamentada con
citas → no-answer → bloqueo por inyección → PII pregunta/respuesta → auditoría → evaluación) y el
defecto real encontrado y corregido durante esa verificación (sección 33bis).

## 24. Gestión de configuración

`insurance-ai.*` tipado vía `@ConfigurationProperties` (`InsuranceAiProperties`), separación
`application.yaml`/`application-test.yaml` estrictamente respetada — ningún valor de producción
fue rebajado para hacer pasar un test (verificado explícitamente en la auditoría FASE 13).

## 25. Docker / infraestructura local

`docker-compose.yml`: PostgreSQL+pgvector, Kafka (KRaft), Kafka UI, y (desde FASE 15) el servicio
`insurance-ai` — la imagen multi-stage que compila Angular, copia su salida a
`src/main/resources/static/`, y empaqueta el jar de Spring Boot, sirviendo la UI y la API completas
en un único proceso/puerto (`8080`). Sin Elasticsearch, Redis, API Gateway ni Kubernetes. Build y
despliegue real verificados en FASE 15 (`docker compose build --no-cache` + `docker compose up -d`
con los 4 servicios alcanzando `healthy`), no solo `docker compose config`.

## 26. Estrategia de testing y Testcontainers

Pirámide de tests: unitarios sin Docker/Spring/red, integración con PostgreSQL/Kafka reales
(Testcontainers, contenedores reutilizados vía `.withReuse(true)` + caché de `ApplicationContext`
de Spring), E2E manual documentado en `docs/demo/DEMO_GUIDE.md`. Ver
`docs/testing/TESTCONTAINERS.md`.

## 27. ArchUnit

5 reglas: dominio libre de frameworks, application sin depender de adapters/infrastructure,
dirección de dependencias hexagonal, ports como interfaces, adapters outbound implementando un
port — verde en cada build completo de este proyecto, nunca debilitada. FASE 14 amplió (nunca
relajó) la lista de paquetes prohibidos para `domain` (`io.micrometer`, `java.sql`) tras un
hallazgo de cobertura (ARCH-01, LOW) - el dominio ya estaba limpio de ambos en la práctica,
verificado por grep directo, no solo confiando en la regla.

## 28. Índice de ADRs

ADR-001 (Hexagonal) · 002 (Modular Monolith) · 003 (Document Aggregate Boundaries) · 004 (Document
Processing Failure Policy) · 005 (Embedding as Derived Projection) · 006 (Advanced RAG Retrieval) ·
007 (GenAI Security Guardrails) · 008 (AI Governance Foundation) · 009 (AI Evaluation Foundation) ·
010 (Observability and Resilience) · 011 (API Documentation) · 012 (FASE 14 Audit Remediation) ·
013 (Frontend Architecture) — 13 ADRs, numeración secuencial sin huecos ni duplicados.

## 29. Índice de documentación

34 archivos Markdown bajo `docs/` (adr, architecture, audit, demo, evaluation, frontend, governance,
observability, rag, resilience, security, testing) + `README.md`, `FINAL_ARCHITECTURE_AUDIT.md` y
`FINAL_FRONTEND_AUDIT.md` en la raíz. Mapa completo en `README.md` sección "Documentation map".

## 30. Registro de honestidad de componentes fake/heurísticos

| Componente | Naturaleza declarada |
|---|---|
| `FakeLlmAdapter` | Determinista, offline, prefija cada respuesta con `[FAKE PROVIDER - not a real LLM call]` |
| `FakeEmbeddingModelAdapter` | Heurístico de solapamiento de palabras, no embeddings semánticos reales |
| `RuleBasedPromptInjectionGuard` | Basado en patrones regex, no clasificador ML |
| `RuleBasedPiiGuard` | Basado en patrones regex, no NER/ML |
| `RuleBasedReranker` | Heurístico, no cross-encoder ni modelo de reranking real |

## 31. Resultados de la validación manual end-to-end

Ejecutada en FASE 13 contra `docker compose` real (no Testcontainers) con `insurance-ai.ai.
provider=fake`: subida de PDF → Kafka → extracción → chunking → embedding → pgvector → pregunta →
retrieval híbrido (`HYBRID`, ambas ramas contribuyendo) → respuesta fundamentada con 2 citas →
`traceId` correlacionado → registro de auditoría real (`promptKey`/`promptVersion`/`latencyMs`
poblados). No-answer explícito verificado. Bloqueo de prompt injection verificado (`outcome:
BLOCKED_BY_GUARDRAIL`). PII detectada sin bloquear verificado (`piiDetectedInQuestion: true`,
`outcome: NO_ANSWER`, no `BLOCKED_BY_GUARDRAIL`). Evaluación: `FAILED` con evidencia parcial,
`PASSED` (`groundingRate`/`recallAtK`/`noAnswerAccuracy` = 1.0) tras ingerir los 4 documentos del
dataset — ambos resultados observados y documentados en `docs/demo/DEMO_GUIDE.md`.

## 32. Comprobaciones estáticas finales

Cero secretos hardcodeados, cero TODO/FIXME en `src/main`/`src/test`, cero tests `@Disabled`/
`@Ignore`, cero `System.out`/`printStackTrace` en `src/main`, `.env` correctamente ignorado por
git. Escaneo exhaustivo FASE 14 de los 67 archivos de test (194 métodos `@Test`): cero
`assertTrue(true)`, cero `assertNotNull(mock(...))`, cero bloques `catch` vacíos, cero
comparaciones tautológicas.

## 33. Matriz de tests final

`./mvnw clean verify` ejecutado **dos veces consecutivas** tras la remediación FASE 14 (requisito
de estabilidad de Testcontainers) — ambas: **276 tests, 0 fallos, 0 errores, 0 omitidos**, 61
clases de test, `BUILD SUCCESS`, exit code real `0` (58.2s la segunda pasada). Categorías
cubiertas: Architecture (ArchUnit), Document, RAG, Hybrid Retrieval, Vector, Lexical, Security,
Governance, Audit, Evaluation, Integration, Kafka, PostgreSQL, Observability (Actuator), API
(OpenAPI docs) — todas verdes. Incidencia diagnosticada y resuelta durante esta fase: la primera
ejecución posterior a la remediación falló por contención de recursos Docker (un contenedor Kafka
reutilizado con 6 horas de estado acumulado de esta sesión, más contenedores de otros proyectos no
relacionados compitiendo por el mismo daemon Docker) - diagnosticado como la causa raíz documentada
en `docs/testing/TESTCONTAINERS.md` sección 6, no una regresión de código; resuelto reciclando los
contenedores Testcontainers de este proyecto (no los del usuario, no los de otros procesos) antes
de reintentar. 10 tests nuevos respecto al cierre de FASE 13 (266 → 276): 6 en
`AskInsuranceKnowledgeUseCaseTest`, 4 en los tests de los adaptadores LLM.

## 33bis. FASE 15 - integración frontend, Docker y hallazgo real

Backend: 2 tests nuevos respecto al cierre de FASE 14 (276 → **286**), ambos surgidos de la
verificación E2E manual contra el stack Docker real, no de desarrollo especulativo:
`GlobalExceptionHandlerTest.shouldMapHttpRequestMethodNotSupportedExceptionTo405NotTheGeneric500`
y `SpaWebConfigurationIntegrationTest.aRealApiPathCalledWithTheWrongHttpMethodIsA405NeverTheSpaFallbackOrA500`.
Causa raíz del defecto que motivó estos tests: `HttpRequestMethodNotSupportedException` (Spring
MVC, lanzada cuando una ruta `/api/**` real existe pero se invoca con el método HTTP incorrecto) no
tenía `@ExceptionHandler` propio, cayendo en el genérico y devolviendo `500` en vez de `405` -
misma categoría que el hallazgo `NoResourceFoundException` ya corregido durante la construcción de
FASE 15. Confirmado leyendo el stack trace real en los logs del contenedor Docker, corregido con un
handler nuevo siguiendo el patrón ya existente, verificado con `./mvnw clean verify` (**286 tests,
0 fallos, 0 errores, 0 omitidos, `BUILD SUCCESS`**), y reverificado contra la imagen Docker
reconstruida y redesplegada. Frontend: 47 tests Vitest (sin cambios en esta fase de auditoría
final, ya estables). Ver `FINAL_FRONTEND_AUDIT.md` sección 15 para el detalle completo
síntoma/causa raíz/corrección/test, y sección 14 para el resto del flujo E2E manual (subida real de
PDF, ingestión Kafka, embedding, pregunta fundamentada, no-answer, bloqueo de inyección, PII
pregunta/respuesta, auditoría, evaluación) ejecutado contra el contenedor en ejecución.

## 34. Verificación del quality gate

Sin `BUILD FAILURE`, sin fallo de ArchUnit, sin tests en rojo, sin secretos expuestos, sin stack
traces expuestos al cliente, sin configuración de producción modificada para pasar tests, sin
dominio contaminado por Spring, sin gobernanza/auditoría/evaluación/seguridad "documentada pero no
implementada" — **quality gate superado**.

## 35. Production Gap Analysis

Explícitamente fuera de alcance de este PoC (ni implementado ni simulado como implementado): IAM/
autenticación/autorización en cualquier endpoint (incluyendo la UI Angular, que no está detrás de
ningún login), API Gateway, rate limiting, WAF, integración SIEM, tracing distribuido más allá del
`traceId` MDC personalizado (ahora también expuesto en la UI vía `TechnicalDetails`), PostgreSQL/
Kafka gestionados o de alta disponibilidad, backup/recuperación ante desastres, model risk
management, monitorización de modelos de terceros, revisión legal/compliance/DPO formal de la
autoevaluación AI Act, revisión de seguridad independiente o pentesting, política de retención de
datos, proceso formal de respuesta a incidentes, i18n/dark mode, y una suite automatizada de
pruebas de navegador (Playwright/Cypress) - la validación E2E del frontend es manual/scripted
contra el stack Docker real, no un suite headless automatizada (ver `FINAL_FRONTEND_AUDIT.md`
secciones 17-18).

## 36. Limitaciones conocidas (consolidado)

Guardias de seguridad basadas en reglas, no ML de producción; reranking heurístico, no
cross-encoder; dataset de evaluación de 7 casos (prueba el mecanismo, no significancia
estadística); sin Precision@K; sin comparación automática entre proveedores; sin circuit breaker
ni failover automático entre proveedores LLM; sin autenticación en ninguna API (`/api/**`,
`/actuator/**`, `/swagger-ui.html`, `/v3/api-docs`); sin UI web, solo API REST;
`insurance-ai.rag.lexical.min-rank` (FASE 14) queda en `0.0` por defecto y no está calibrado
contra ningún corpus real; sin test que verifique que el timeout HTTP configurado
(`spring.http.clients.*`) se cumple realmente bajo condiciones de red reales (limitación
documentada deliberadamente, para evitar un test de red potencialmente inestable). Frontend (FASE
15): `RuleBasedPiiGuard` sigue cubriendo solo 4 patrones españoles (email/teléfono/IBAN/DNI-NIE),
sin número de tarjeta de pago ni cobertura de otros países; sin i18n ni dark mode (evaluados y
descartados deliberadamente); sin suite automatizada de navegador (Playwright/Cypress).

## 37. Recomendaciones para un eventual camino a producción

1. Introducir IAM/OAuth2 y autorización por rol antes de exponer `/api/governance/**` o
   `/actuator/**` fuera de una red de confianza.
2. Sustituir los guardarraíles basados en reglas por servicios de clasificación ML/gestionados
   (prompt injection y PII) tras validación independiente.
3. Ampliar el dataset de evaluación con casos curados por expertos del dominio y suficientes
   juicios de relevancia para computar Precision@K honestamente.
4. Revisión legal/compliance formal de la autoevaluación AI Act antes de cualquier uso con datos
   reales de clientes.
5. Añadir observabilidad de producción (tracing distribuido, métricas centralizadas, alerting)
   más allá de lo que Actuator expone localmente.
6. Evaluar alta disponibilidad de PostgreSQL/Kafka y una estrategia de backup/DR real.
7. Introducir Playwright/Cypress para los flujos de UI críticos (subida→pregunta→cita, bloqueo por
   inyección) como red de regresión visual/interactiva, complementando la suite Vitest actual.
8. Ampliar `RuleBasedPiiGuard` con un patrón de tarjeta de pago si el caso de uso real llega a
   manejarlas, documentando el nuevo alcance igual que los 4 patrones actuales.

## 38. Conclusión y resultado final de tests

El proyecto cierra las 13 fases de entrega originales, más una FASE 14 de auditoría técnica
independiente del backend, más una FASE 15 que añade un frontend Angular completo y su propia
verificación final, con arquitectura hexagonal intacta y verificada mecánicamente, seguridad y
gobernanza genuinamente implementadas (no solo documentadas, y re-verificadas de forma
independiente en dos ocasiones), un pipeline RAG híbrido completo con política de no-answer y
citas verificables, trazabilidad end-to-end demostrada con infraestructura real - ahora incluyendo
una UI real -, y una suite de pruebas backend de **286 tests en verde, `BUILD SUCCESS`, exit code
real `0`**, más 47 tests Vitest en el frontend. La auditoría FASE 14 encontró y remedió 3 hallazgos
HIGH genuinos, 1 MEDIUM y varios LOW/INFO menores en el backend. La auditoría FASE 15 encontró y
remedió 1 hallazgo real adicional durante la verificación E2E manual contra el stack Docker en
ejecución (`HttpRequestMethodNotSupportedException` sin mapear devolviendo `500` en vez de `405`),
e investigó a fondo una observación sobre detección de PII que se confirmó como diseño intencionado
y ya documentado, no un defecto (ver `FINAL_FRONTEND_AUDIT.md` secciones 15-16). Sin ningún hallazgo
CRITICAL en ninguna de las dos auditorías, sin ninguna capacidad de decisión automatizada de
seguros, sin ninguna afirmación de cumplimiento legal indebida. Ningún componente fake se presenta
como real; ninguna limitación se oculta; ningún test se debilitó ni se eliminó para lograr este
resultado. Ver `FINAL_ARCHITECTURE_AUDIT.md` (FASE 14) y `FINAL_FRONTEND_AUDIT.md` (FASE 15) para
el detalle completo, hallazgo por hallazgo. El proyecto - backend y frontend, empaquetados en una
única imagen Docker verificada en ejecución real - está listo para ser revisado como referencia
arquitectónica — no para ser desplegado en producción sin las revisiones de la sección 37.

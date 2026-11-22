# ADR-002: Modular Monolith over Microservices

## Status

Accepted — FASE 0.

## Context

The brief defines six bounded contexts (Document Management, RAG, AI Governance, AI Audit,
AI Evaluation, and the cross-cutting AI System concept) plus infrastructure integrations
(PostgreSQL/pgvector, Kafka, two LLM providers). The brief explicitly scopes this as a
**local PoC** (§3) and explicitly forbids introducing Kubernetes, a service mesh, an API
gateway, an LLM gateway service, or "artificial microservices" (§3, §16). At the same time,
it requires clean bounded-context separation with DDD (§5).

## Decision

Build a **single Spring Boot deployable** ("modular monolith") in which each bounded context
from §5/§6 of the brief is a set of packages (`domain.<context>`, `application.<context>`)
with its own aggregates, use cases, and ports, but all running in one JVM process, one Maven
build, and (initially) one PostgreSQL schema. Cross-context communication happens either:

- synchronously, through an explicitly exposed application-layer port (e.g. RAG's use case
  reads governance data such as the active prompt version through a `PromptRegistryPort`),
  never by reaching into another context's domain/repository directly; or
- asynchronously, through Kafka domain events, for the one workflow that is genuinely
  asynchronous by nature — document ingestion (brief §33).

This keeps the modularity benefit of DDD (clear ownership, replaceable pieces, testable
boundaries — see ADR-001) without paying the operational cost of distributed systems
(network partial failure, distributed tracing across services, deployment orchestration,
service discovery) that brief §3 explicitly rules out of scope for this PoC.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| One microservice per bounded context (Document, RAG, Governance, Audit, Evaluation) | Directly contradicts brief §3 ("no microservicios artificiales", "no infraestructura distribuida innecesaria"). Would require inter-service auth, service discovery, and distributed tracing infrastructure that adds no demonstrable value to the architectural story this PoC is telling. |
| Single unstructured Spring Boot app (no explicit bounded contexts) | Fast to build, but reintroduces the coupling problem ADR-001 exists to prevent, and cannot demonstrate governance/audit as first-class, isolable capabilities as required by brief §23. |
| Multi-module Maven build (one Maven module per bounded context) | Considered for stronger compile-time isolation. Rejected for this PoC's size: it adds Maven reactor/build-order complexity without a corresponding team-scaling need (single team, single deployable). Package-level boundaries plus ArchUnit (ADR-001) give equivalent enforcement with far less build overhead. May be revisited via a future ADR if the codebase grows enough to justify it. |

## Consequences

- Positive: one `docker compose up -d` + one `./mvnw spring-boot:run` is enough to run the
  whole platform, matching the developer experience mandated in brief §35/§65.
- Positive: transactions that must be atomic across, e.g., audit-event write and use-case
  execution stay in a single database/process, avoiding distributed-transaction complexity
  that would otherwise need to be designed and explained.
- Cost: all contexts share one JVM's failure domain (a fatal error in one module can affect
  the whole process) and one deployment cadence. Acceptable for a PoC; would be revisited
  (with a new ADR) if this evolved toward a production multi-team system.
- Future evolution path: because contexts already communicate only through explicit ports
  and Kafka events (never shared repositories), extracting a bounded context into its own
  service later is a boundary-preserving refactor, not a redesign.

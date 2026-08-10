# ADR-001: Domain-Driven Design + Hexagonal Architecture

## Status

Accepted — FASE 0.

## Context

The system is a RAG-based insurance knowledge assistant that must integrate multiple
volatile external concerns: two LLM providers (OpenAI, Anthropic), an embedding model, a
vector store (PostgreSQL/pgvector), Kafka-based document ingestion, and PDF text extraction.
It must also satisfy AI Governance requirements (auditability, risk classification, human
oversight) that are business rules, not infrastructure details, and therefore must not be
entangled with any single technical framework or vendor SDK.

A conventional `controller / service / repository` layering would let Spring, Spring AI, and
vendor-specific types (e.g. `org.springframework.ai.chat.model.ChatModel`,
`org.springframework.ai.document.Document`) leak into business logic, making it impossible to:

- unit test domain rules (risk classification, grounding validation, PII rules) without
  booting a Spring context or Testcontainers;
- swap an LLM/embedding/reranking provider without touching business logic;
- reason about and enforce architectural boundaries mechanically.

## Decision

Adopt **Domain-Driven Design** combined with **Hexagonal Architecture (Ports & Adapters)**:

- `domain`: pure Java. Entities, Value Objects, Aggregates, Domain Services, Domain Events.
  No Spring, Spring AI, Spring Data, JPA/Hibernate, Kafka client, OpenAI/Anthropic SDK, or
  HTTP types. Where an immutable data carrier is needed, plain Java `record`s are used.
- `application`: use cases (commands/queries) orchestrating domain objects through **ports**
  (interfaces) — e.g. `DocumentRepository`, `EmbeddingModelPort`, `LlmProvider`,
  `VectorSearchPort`, `Reranker`, `PromptInjectionGuard`, `AuditTrailPort`. No framework
  annotations beyond what is unavoidable for Spring to wire the use case as a bean (see
  "Framework annotation exception" below).
- `adapters` (+ `infrastructure` for cross-cutting configuration): implementations of ports
  against Spring AI, PostgreSQL/JDBC, Kafka, OpenAI, Anthropic, Tika/PDFBox, and inbound REST
  controllers. All framework and vendor-specific code lives here exclusively.

Dependencies point inward only: `adapters → application → domain`. `domain` depends on
nothing in this codebase; `application` depends only on `domain`.

### Framework annotation exception

Spring requires *some* annotation (`@Service`, constructor injection, etc.) on application
use-case classes to register them as beans. This is accepted as a pragmatic exception scoped
to the `application` layer only — the **domain** layer remains 100% annotation-free. This
exception itself, and any future one, is to be documented inline where it is used, per
brief §7.

### Boundary enforcement

Package boundaries are not just a convention — they are enforced with **ArchUnit** tests
(introduced FASE 1), which fail the build if:

- `domain` references any package under `org.springframework.*`, `com.fasterxml.jackson.*`,
  `jakarta.persistence.*`, `org.apache.kafka.*`, or any LLM vendor SDK;
- `application` references `adapters` or `infrastructure`;
- an adapter class exists that does not implement a port defined in `application`/`domain`.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Classic layered architecture (`controller/service/repository`) | Fastest to start, but couples business rules (risk classification, grounding, guardrails) to Spring/JPA/vendor SDKs; makes provider-swapping and governance auditability much harder to guarantee mechanically. |
| Full Clean Architecture with a separate `usecases` module per Maven module | Adds build/module-graph overhead disproportionate to a single-team PoC; package-level boundaries plus ArchUnit achieve the same isolation without multi-module Maven complexity. |
| No explicit ports for LLM/embedding providers (call Spring AI directly from application) | Simpler short-term, but directly violates brief §16/§17 (provider must be swappable via configuration, application layer must stay provider-agnostic) and would require rewriting use cases to add a second LLM provider. |

## Consequences

- Positive: domain rules (risk classification, PII detection rules, grounding/no-answer
  logic, chunk versioning rules) are testable with plain JUnit, no Spring context required.
  Swapping OpenAI ↔ Anthropic, or the reranker implementation, touches only one adapter.
- Positive: the architecture is self-documenting for the audiences named in brief §57
  (architects, security reviewers, compliance) — module boundaries visibly express what each
  bounded context is responsible for.
- Cost: more files/interfaces than a layered approach for equivalent functionality; requires
  discipline (enforced by ArchUnit) to avoid domain leakage over time.

## Related decision: root package rename

The scaffolded root package `com.rag.springai.rag_springai_prueba` is renamed to
`com.rag.springai.insuranceai` to give the bounded-context sub-packages
(`insuranceai.domain.document`, `insuranceai.application.rag`, ...) a meaningful,
non-generated name. The Maven `groupId`/`artifactId` scheme (`com.rag.springai`) and the
existing repository name are kept as-is since they are not in conflict with DDD naming and
changing them is not necessary. Executed as part of FASE 1.

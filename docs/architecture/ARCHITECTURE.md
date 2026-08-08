# Architecture Overview — Insurance Knowledge Assistant

Status: living document, initial version created in FASE 0, refined every phase.

## 1. Mission

Demonstrate how an insurance company can introduce Generative AI in a controlled,
traceable, secure and governed way, using a Retrieval-Augmented Generation (RAG)
architecture over corporate documentation — without turning the system into an
unaccountable black box. See `docs/governance/AI_ACT.md` (created in FASE 9) for the
regulatory framing and `PROJECT_DISCOVERY.md` for the environment/version baseline this
architecture builds on.

## 2. Architectural style

- **Domain-Driven Design (DDD)** — the codebase is organized around insurance-knowledge
  bounded contexts, not technical layers alone.
- **Hexagonal Architecture (Ports & Adapters)** — dependencies always point inward, toward
  the domain. Application services depend on ports (interfaces); adapters implement those
  ports against Spring AI, PostgreSQL, Kafka, OpenAI, Anthropic, etc.
- **Modular Monolith** — a single deployable Spring Boot application containing multiple
  well-isolated modules (see ADR-002). No microservices are introduced artificially.

## 3. Layering

```
                 ┌─────────────────────┐
                 │       DOMAIN        │   Entities, Value Objects, Aggregates,
                 │                     │   Domain Services, Domain Events.
                 │                     │   Zero framework dependencies (see ADR-001 §4).
                 └──────────▲──────────┘
                            │
                 ┌──────────┴──────────┐
                 │    APPLICATION      │   Use cases, Commands/Queries, Ports
                 │                     │   (inbound + outbound interfaces).
                 └──────────▲──────────┘
                            │
                 ┌──────────┴──────────┐
                 │      ADAPTERS       │   REST controllers, Kafka producers/consumers,
                 │                     │   PostgreSQL/JDBC repositories, Spring AI
                 │                     │   ChatClient/EmbeddingModel/VectorStore,
                 │                     │   OpenAI/Anthropic clients, PDF readers.
                 └─────────────────────┘
```

Enforcement of these boundaries is automated with ArchUnit tests, introduced in FASE 1 and
mandatory for every subsequent phase (brief §37).

## 4. Bounded contexts

| Context | Responsibility |
|---|---|
| **Document Management** | Documents, versions, chunks, metadata, ingestion lifecycle. |
| **RAG** | Questions, hybrid retrieval, reranking, context assembly, grounding, citations. |
| **AI Governance** | AI System Registry, Model Registry, Prompt Registry, risk classification, human oversight metadata. |
| **AI Audit** | Traceable record of every AI execution: model, prompt version, retrieval scores, tokens, latency, guardrail events. |
| **AI Evaluation** | Evaluation datasets, expected answers/sources, metric runners, results. |

Each context owns its `domain` and `application` slice under a dedicated package; contexts
communicate through application-layer ports and, where genuinely asynchronous (document
ingestion), through Kafka domain events — never through shared mutable state or direct
cross-context repository access.

## 5. Primary flow (chat / question answering)

```
Employee → Insurance AI Assistant → Security checks (PromptInjectionGuard, PiiDetector)
   → Query processing → Hybrid retrieval (semantic + keyword + metadata filtering)
   → Reranking → Context validation → Prompt construction (Prompt Registry)
   → LLM (OpenAI or Anthropic, via LlmProvider port) → Output guardrails
   → Grounding validation → Grounded response + sources + traceId
```

This flow, its security posture and its no-answer strategy are detailed in
`docs/rag/RAG_DESIGN.md` and `docs/security/SECURITY.md` (created in FASE 5/6/8).

## 6. Document ingestion flow (asynchronous, Kafka-backed)

```
PDF upload → hash (SHA-256, idempotency) → insurance.document.uploaded
   → Tika/PDFBox extraction → cleaning → structure-aware chunking
   → insurance.document.processed → embedding generation
   → insurance.document.embedded → PostgreSQL + pgvector
```

Detailed in `docs/rag/CHUNKING.md` and `docs/rag/EMBEDDINGS.md` (created in FASE 4).

## 7. What this system intentionally does NOT do

Per brief §26/§47, this PoC is deliberately scoped to **internal knowledge assistance**. It
does not perform insurance risk assessment, pricing, eligibility determination, autonomous
claims decisions, or customer profiling. The AI informs; a human decides. This constraint is
enforced architecturally (no such use case, port, or endpoint exists) and is restated in the
AI System Registry entry and system prompt — see `docs/governance/AI_GOVERNANCE.md`.

## 8. Status of this document

Created during FASE 0 as the anchor for later, more detailed architecture documents
(`docs/architecture/C4.md`, `docs/architecture/COMPONENTS.md`) and the ADR series in
`docs/adr/`. It will be expanded, not rewritten, as each phase lands.

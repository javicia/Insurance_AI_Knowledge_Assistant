# Project Discovery — Insurance Knowledge Assistant (RAG Enterprise Platform PoC)

Status: FASE 0 — Discovery
Date: 2026-08-08

## 1. Purpose of this document

This document records the state of the repository and local environment as found at the
start of the project, the compatibility research performed before pinning dependency
versions, and the architectural direction proposed for the PoC. It is the baseline against
which all later phases (see `docs/adr/` and `docs/architecture/`) are evaluated.

## 2. Repository state

- Git repository: yes. Branch `feature/structure`, single commit (`a760b05 Initial commit`).
- Working tree: the initial commit contains a bare Spring Initializr skeleton. There are
  uncommitted modifications to `pom.xml` and four Java files, all of which are whitespace /
  metadata changes (indentation normalized to 4 spaces, `artifactId`/`name` edited) — no
  business logic exists yet anywhere in the repository.
- No `docs/`, `documents/`, `docker-compose.yml`, `.env.example`, or Flyway migrations exist.
- No domain, application, adapter, or port packages exist. The only production class is the
  default `RagSpringaiPruebaApplication` boot entry point.

### Existing files

```
pom.xml
src/main/java/.../RagSpringaiPruebaApplication.java
src/main/resources/application.yaml   (only spring.application.name)
src/test/java/.../RagSpringaiPruebaApplicationTests.java
src/test/java/.../TestRagSpringaiPruebaApplication.java
src/test/java/.../TestcontainersConfiguration.java  (PostgreSQL/pgvector Testcontainers already wired)
```

**Conclusion: the repository is effectively empty of implementation.** This PoC is being
built from scratch on top of a Spring Initializr scaffold, not migrated from an existing
system. Section 59 ("no destruyas código existente") therefore does not constrain design
decisions — there is nothing to preserve except the scaffold's dependency choices, which
this document validates below.

### Pre-existing issue found in `pom.xml`

The uncommitted `pom.xml` sets:

```xml
<artifactId>Insurance AI Knowledge Assistant — RAG Enterprise Platform</artifactId>
<name>rInsurance AI Knowledge Assistant — RAG Enterprise Platform</name>
```

A Maven `artifactId` must not contain spaces or non-ASCII punctuation (it is used verbatim
in the final jar filename and repository path). This will be corrected in FASE 1 to
`insurance-knowledge-assistant`, with the descriptive title kept only in `<name>`/`<description>`.
Flagged here for transparency, not fixed yet, since FASE 0 is discovery-only.

## 3. Local toolchain inventory

| Tool | Found | Path |
|---|---|---|
| Maven Wrapper | 3.9.16 | `.mvn/wrapper/maven-wrapper.properties` |
| JDK used by `JAVA_HOME` (drives the wrapper) | 21.0.11 (Microsoft Build of OpenJDK) | `D:\Javier\Java\.jdks\jdk-21.0.1` |
| JDK 17 | 17.0.12 LTS | `C:\Program Files\Java\jdk-17` |
| JDK 11 | Microsoft Build 11.0.16 | `C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot` |
| JRE 8 | 1.8.0_501 | `C:\Program Files\Java\jre1.8.0_501` (also first on `PATH`, hence `java -version` reports 8) |
| "graalvm-jdk-25-1" | **empty directory, not actually installed** | `D:\Javier\Java\.jdks\graalvm-jdk-25-1` |
| "loom-ea-25-loom+1-11" | Functional, but is an **Oracle Project Loom early-access build**, not a standard JDK 25 GA distribution | `D:\Javier\Java\.jdks\loom-ea-25-loom+1-11` (`openjdk version "25-loom" 2025-09-16`, `JAVA_RUNTIME_VERSION="25-loom+1-11"`) |
| **JDK 25 GA (resolved, see below)** | **25.0.4 LTS, Microsoft Build of OpenJDK** | `D:\Javier\Java\.jdks\jdk-25.0.4+7` |
| Docker | 29.6.1 | — |
| Docker Compose | v5.2.0 | — |

### ✅ Resolved: standard Java 25 GA JDK installed

FASE 0 originally flagged that no standard Java 25 GA JDK was present locally (only JDK
21/17/11/8 and the experimental Loom EA build above). This was raised as an open decision;
you chose to install a real JDK 25 GA. `winget` was not available and Chocolatey required
elevated/admin rights not available in this environment, so **Microsoft Build of OpenJDK
25.0.4 LTS** was downloaded directly from the official `aka.ms` distribution point and
extracted to `D:\Javier\Java\.jdks\jdk-25.0.4+7`, following the same layout convention as
the pre-existing JDK 21 install. Verified functional:

```
openjdk version "25.0.4" 2026-07-21 LTS
OpenJDK Runtime Environment Microsoft-14670760 (build 25.0.4+7-LTS)
OpenJDK 64-Bit Server VM Microsoft-14670760 (build 25.0.4+7-LTS, mixed mode, sharing)
```

The user-scope `JAVA_HOME` environment variable was persisted (`setx`) to point at this
JDK, so it applies automatically in **new** terminals/IDE sessions. The tool session used to
build this project was already running when the change was made, so within that session
`JAVA_HOME` was passed explicitly on each Maven invocation instead — this is a limitation of
the execution environment, not of the JDK install itself, and disappears once a fresh
terminal/IDE session is opened. The `pom.xml`'s `<java.version>25</java.version>` (see §4)
is therefore buildable today, with no further environment gap.

## 4. Dependency & version compatibility research

Per section 2/58 of the brief, no version was assumed — the following was verified against
current (August 2026) official sources before being accepted or changed.

| Component | Version already in `pom.xml` | Verified status |
|---|---|---|
| Spring Boot | `4.1.0` | Latest stable release (GA June 10, 2026). Requires Java 17 minimum, **supports up to Java 26** — Java 25 is within the supported range. Requires Spring Framework 7.0.8+. |
| Spring AI | `2.0.0` (via `spring-ai-bom`) | Latest stable GA (May 28, 2026). Officially designed for **Spring Boot 4.0.x and 4.1.x** + Spring Framework 7.0. `2.0.1` only exists as a `-SNAPSHOT`, not a release — correctly not used. |
| Java | `25` (LTS, GA September 2025) | Compatible with both Spring Boot 4.1.0 and Spring AI 2.0.0 **once a real JDK 25 GA runtime is used to build** (see §3 blocker). |

**Conclusion: the version combination Java 25 / Spring Boot 4.1.0 / Spring AI 2.0.0 already
present in the scaffold is correct and requires no downgrade.** The only gap is local
toolchain availability, not dependency compatibility.

Known related upstream issue (informational, not blocking): `spring-projects/spring-ai#6465`
notes Spring AI 2.0.0 starters transitively pull Spring Boot 4.1.0 even where docs mention
4.0.x compatibility — irrelevant here since we target 4.1.0 directly.

Sources consulted: spring.io release blogs for Spring Boot 4.1.0 and Spring AI 2.0.0 GA,
`spring-boot` and `spring-ai` GitHub release-notes wikis, HeroDevs Spring Boot/Spring AI
compatibility posts.

## 5. Proposed final architecture (summary)

Full detail will live in `docs/architecture/ARCHITECTURE.md` and `docs/adr/`. Summary:

- **Style**: Domain-Driven Design + Hexagonal Architecture (Ports & Adapters), packaged as a
  single **Modular Monolith** Spring Boot application (ADR-002) — no microservices, no
  external gateway/mesh, consistent with the "local PoC" scope in section 3 of the brief.
- **Bounded contexts**: AI System (Governance), Document Management, RAG, AI Governance,
  AI Audit, AI Evaluation — each with its own `domain` / `application` / `ports` slice, sharing
  one `adapters`/`infrastructure` layer per section 6 of the brief.
- **Root package**: `com.rag.springai.insuranceai` (keeps the existing Maven `groupId`
  `com.rag.springai` already committed in the initial commit, replaces the meaningless
  `rag_springai_prueba` artifact package with a domain-meaningful one). This is a naming
  clean-up, not a scope change, and will be recorded as part of ADR-001.
- **Persistence**: PostgreSQL + pgvector via Spring AI's `PgVectorStore` abstraction; schema
  managed by Flyway (no ad-hoc SQL init).
- **Messaging**: Kafka, used exclusively for the async document-ingestion pipeline
  (`insurance.document.uploaded` → `...processed` → `...embedded`); chat stays synchronous.
- **LLM/Embeddings**: Spring AI `ChatClient`/`EmbeddingModel`, OpenAI and Anthropic both
  wired behind an internal `LlmProvider` port, selected via
  `insurance-ai.ai.provider` configuration — no separate LLM Gateway service.
- **Security**: `PromptInjectionGuard`, `PiiDetector`/`PiiSanitizer`, output grounding
  validation — all as explicit domain/application ports with PoC-grade (rule/regex-based)
  adapters, documented as such rather than presented as production ML capabilities.
- **Governance**: AI System Registry, Model Registry, Prompt Registry, Risk Assessment,
  Audit Trail, Human Oversight and Evaluation are first-class bounded contexts with real
  aggregates and REST APIs, not just database tables.

## 6. Phased delivery plan

Following section 56 of the brief, delivery proceeds in 13 phases (FASE 1 – Architecture
Foundation, through FASE 13 – Final Hardening), each gated on `./mvnw test` /
`./mvnw verify` passing and closed with the Spanish-language report format defined in
section 62. FASE 0 is closed (JDK 25 GA resolved, see §3); FASE 1 is in progress.

## 7. FASE 0 decisions log

- Java 25 GA toolchain gap: resolved by installing Microsoft Build of OpenJDK 25.0.4 LTS
  locally (see §3). No architectural or scope change resulted from this — it was a pure
  local-environment gap, not a dependency-compatibility issue.

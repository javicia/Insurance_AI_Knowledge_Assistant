# Testcontainers strategy

Status: living document, established during the "Testcontainers Stability" audit (FASE 6). This
is the single source of truth for how integration tests obtain PostgreSQL/Kafka - read this
before adding a new `@SpringBootTest` class or debugging a container startup timeout.

## 1. Lifecycle: two independent reuse mechanisms, stacked

Two separate, complementary mechanisms make containers "shared" rather than started/stopped per
test:

1. **Spring's `ApplicationContext` cache** (within one JVM/Maven invocation). Every
   `@SpringBootTest` class in this project uses the exact same context configuration -
   `@Import(TestcontainersConfiguration.class)` + `@ActiveProfiles("test")`, nothing else
   class-specific (no per-class `@TestPropertySource`). Spring's `DefaultContextCache` keys
   contexts by their full `MergedContextConfiguration`; identical configuration across classes
   means Spring reuses one already-built context - and therefore the one `PostgreSQLContainer`/
   `KafkaContainer` `@Bean` instance it created - instead of building a second one. This is why
   **no class should add a class-specific `@TestPropertySource`/`@DynamicPropertySource`/extra
   `@Import`**: doing so creates a new cache key, and therefore a second container pair, for that
   one class. Test-only configuration values belong in `src/test/resources/application-test.yaml`
   (profile-specific, merged on top of `src/main/resources/application.yaml`, not a replacement
   of it) so every class stays on the same cache key.

2. **Testcontainers' own `withReuse(true)`** (across separate JVM/Maven invocations).
   `TestcontainersConfiguration`'s `pgvectorContainer()`/`kafkaContainer()` beans call
   `.withReuse(true)`. Testcontainers computes a hash of each container's configuration (image,
   env, ports, command) and, if a running container with that exact hash already exists, attaches
   to it instead of starting a new one - skipping Ryuk-based teardown at JVM exit so the container
   survives. This is what lets `./mvnw test -Dtest=SingleClass`, run repeatedly as separate
   processes during local development, avoid paying a cold start every time.

   **Requires an explicit machine-local opt-in**: `~/.testcontainers.properties` must contain
   `testcontainers.reuse.enable=true`. This is deliberate on Testcontainers' part (reuse is
   unsafe to default-on for shared CI runners, where a leftover container from one build could
   leak into another) - it is **not** committed to the repository, and its absence is a silent,
   harmless no-op (containers just behave as before: fresh per JVM, Ryuk-cleaned at exit). Add it
   once per development machine:
   ```
   echo "testcontainers.reuse.enable=true" >> ~/.testcontainers.properties
   ```

Combined: within one `./mvnw clean verify`, all ~9 integration test classes share one Postgres +
one Kafka (mechanism 1); across separate invocations on a machine with reuse enabled, that same
Postgres + Kafka keep running and get reattached to instantly (mechanism 2).

## 2. Test data isolation

Sharing infrastructure does **not** mean sharing test data. Every `@SpringBootTest` class is
annotated `@ExtendWith(DatabaseCleanupExtension.class)`
(`src/test/java/.../DatabaseCleanupExtension.java`), which runs
`TRUNCATE TABLE documents, document_versions, document_version_contents, document_chunks,
vector_store RESTART IDENTITY CASCADE` before every test **method**. This is the single cleanup
mechanism for the whole suite - do not add a per-class `@BeforeEach` that duplicates it (two
earlier per-class `@BeforeEach` methods that did exactly this, in `PgVectorStoreAdapterTest` and
`PostgresLexicalSearchAdapterTest`, were removed in favor of this shared extension).

This is not optional now that containers are reused: before `withReuse(true)`, every fresh
container started with an empty database, which was accidentally providing test isolation across
*separate Maven invocations* as a side effect of always cold-starting. With reuse enabled, that
accidental isolation disappears - `DatabaseCleanupExtension` is what actually provides it,
explicitly, regardless of whether the container is fresh or reused.

One real bug this surfaced immediately: `JdbcDocumentRepositoryTest` used fixed content strings
(e.g. `ContentHash.of("auto premium content".getBytes())`), which produce the same SHA-256 hash
every run; a second `clean verify` invocation against a *reused* (non-empty) database hit
`document_versions`' real `uq_document_versions_content_hash` unique constraint against leftover
rows from the first run. `DatabaseCleanupExtension` fixes this for the whole suite at once, rather
than requiring every test author to remember to randomize content or clean up by hand.

## 3. Why `DocumentIngestionPipelineIntegrationTest` no longer asserts on `PROCESSED`

A second, more subtle effect of consolidating onto one shared context: this class's Spring
context now always also has FASE 5's `DocumentProcessedEventListener`/
`EmbedDocumentVersionUseCase` active in the background (they always were beans in the app
context, in every phase - what changed is that `insurance-ai.ai.provider` is now `fake` for this
context too, via `application-test.yaml`, so embedding actually succeeds instead of failing
forever against a placeholder OpenAI key). The fake embedding provider has no network I/O, so a
version can race from `PROCESSED` to `EMBEDDED` before an `Awaitility` poll ever observes exactly
`PROCESSED`. The fix was to make the FASE 4 test assert on its actual, stable artifact - chunk
existence - rather than a transient status value that a concurrent, unrelated FASE 5 step can
race past. See the class Javadoc for the full explanation.

## 4. Kafka

No manually-constructed `KafkaConsumer`/`KafkaProducer`/`AdminClient` exist anywhere in the test
suite except `InfrastructureAvailabilityTest`'s single `AdminClient.create(...)`, which is
already inside a `try`-with-resources block. Every other Kafka interaction goes through
Spring-managed beans (`@KafkaListener` containers, `KafkaTemplate`), closed automatically when
the (cached, shared) `ApplicationContext` shuts down. No resource leak was found.

The `NOT_COORDINATOR`/rebalance `WARN`/`INFO` log lines visible during the first few seconds
after a fresh Kafka container starts are normal, expected Kafka client protocol chatter during a
single-node KRaft broker's first consumer group formation - not a defect, and not something to
suppress. They stop appearing once reuse means the broker is no longer restarting between runs.

## 5. Parallel execution

Not enabled, deliberately kept that way. No `junit-platform.properties` exists (JUnit 5 defaults
to sequential execution), and `pom.xml` sets no `maven-surefire-plugin` `<configuration>`
(Maven's own default `forkCount=1`, one JVM for the whole `test` phase, running classes
sequentially). Correctness over speed for this PoC, per the audit brief - parallelism would only
multiply concurrent Postgres/Kafka instances on the same Docker Desktop VM for no benefit once
containers are already shared/reused.

## 6. Docker resource contention

Confirmed as a real, secondary contributing factor to observed cold-start timeouts, not the root
cause: the `docker-compose` stack used for manual end-to-end validation
(`insurance-ai-postgres`/`insurance-ai-kafka`/`insurance-ai-kafka-ui`) was left running for
several hours *during* Testcontainers test execution. Spring's `ApplicationContext` cache keeps
every context it creates alive simultaneously (not torn down between test classes), so before
this audit's fixes, up to 7 Postgres/Kafka-family containers could be competing for the same
Docker Desktop VM's CPU/disk at once, occasionally pushing a fresh Postgres `initdb` past the
default 60s `LogMessageWaitStrategy` timeout. **Recommendation**: stop the manual-validation
`docker-compose` stack (`docker compose stop`) before running `./mvnw clean verify` - the two
stacks are independent and only add contention, never benefit, when run together.

## 7. Why not mocks

Every integration test in this suite talks to a real PostgreSQL (with the real `vector`
extension, real Flyway-applied schema, real `tsvector`/GIN full-text search) and, where
Kafka-dependent, a real single-node KRaft broker. Mocking either would stop testing what these
tests exist to prove: that `PgVectorStoreAdapter`'s SQL is valid against real pgvector, that
`PostgresLexicalSearchAdapter`'s `tsquery`/stemming behaviour matches what PostgreSQL actually
does (verified empirically during this project, not assumed), that Kafka's at-least-once
redelivery is actually handled idempotently. Reuse/caching solve the *speed and stability* problem
without weakening what is actually being verified.

## 8. Troubleshooting

- **A single test class times out on container startup, but the full suite doesn't**: almost
  always means reuse isn't active for that invocation (check `~/.testcontainers.properties`) or
  it's a genuine one-off Docker Desktop hiccup - retry once. Check `docker ps` for a container
  already running with a name matching neither `insurance-ai-*` (docker-compose) - Testcontainers
  names are randomly generated (e.g. `goofy_bouman`) - to confirm whether a reusable instance
  already exists.
- **`ApplicationContext` fails to load with a `BeanCreationException` mentioning
  `pgvectorContainer`/`kafkaContainer`**: read the full cause chain - `Container startup failed`
  + `Timed out waiting for log output matching...` is a cold-start timeout (see above), not an
  application bug.
- **A new `@SpringBootTest` class seems to start a second container pair**: check it uses the
  exact same `@Import(TestcontainersConfiguration.class)` + `@ActiveProfiles("test")` combination
  as every other class, with no additional `@TestPropertySource`/`@DynamicPropertySource`.
- **A test fails with a real duplicate-key/unique-constraint error only on a second run**: almost
  always missing `@ExtendWith(DatabaseCleanupExtension.class)`, or new state written to a table
  the extension doesn't yet truncate (add it to the `TRUNCATE` statement).

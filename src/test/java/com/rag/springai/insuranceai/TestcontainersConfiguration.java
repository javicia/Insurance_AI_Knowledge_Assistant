package com.rag.springai.insuranceai;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The single source of truth for this project's integration-test infrastructure (brief
 * "Testcontainers Stability" audit, section 4). Every {@code @SpringBootTest} class {@code
 * @Import}s this same configuration with the same active profile (see {@code
 * application-test.yaml}), so Spring's {@code ApplicationContext} cache treats them as one
 * {@code MergedContextConfiguration} and reuses one running Postgres + one running Kafka across
 * the entire suite within a single {@code ./mvnw} invocation - see
 * {@code docs/testing/TESTCONTAINERS.md} for the full lifecycle explanation.
 *
 * <p>{@code withReuse(true)} additionally lets both containers survive *across* separate JVM/
 * Maven invocations (e.g. re-running a single test class during local development), eliminating
 * repeated cold starts entirely once a container has started once - opt-in per Testcontainers'
 * own safety design via {@code ~/.testcontainers.properties}'
 * {@code testcontainers.reuse.enable=true} (a machine-local developer setting, never committed
 * to the repo; harmless no-op if absent - see {@code docs/testing/TESTCONTAINERS.md}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer pgvectorContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")).withReuse(true);
    }

    @Bean
    @ServiceConnection("kafka")
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:latest")).withReuse(true);
    }

}

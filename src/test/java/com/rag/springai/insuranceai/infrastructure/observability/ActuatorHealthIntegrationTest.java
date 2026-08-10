package com.rag.springai.insuranceai.infrastructure.observability;

import com.rag.springai.insuranceai.DatabaseCleanupExtension;
import com.rag.springai.insuranceai.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FASE 11 (Observability) acceptance check: {@code spring-boot-starter-actuator}'s health
 * aggregation genuinely reports {@code UP} against the real Testcontainers-managed PostgreSQL and
 * Kafka - not just that the dependency compiles. See {@code docs/observability/OBSERVABILITY.md}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(DatabaseCleanupExtension.class)
class ActuatorHealthIntegrationTest {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Test
    void overallHealthIsUpWithRealPostgresAndKafka() {
        assertEquals(Status.UP, healthEndpoint.health().getStatus());
    }
}

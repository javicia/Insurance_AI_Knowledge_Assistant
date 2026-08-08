package com.rag.springai.insuranceai.infrastructure;

import com.rag.springai.insuranceai.TestcontainersConfiguration;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 2 (Local Infrastructure) acceptance checks: proves PostgreSQL, pgvector, Flyway and
 * Kafka are reachable and correctly wired, using the same Testcontainers-managed instances as
 * every other Spring context test. No business/domain code is exercised here.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InfrastructureAvailabilityTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Test
    void postgresIsAccessible() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);

        assertEquals(1, result);
    }

    @Test
    void pgvectorExtensionIsInstalled() {
        List<String> extensions = jdbcTemplate.queryForList(
                "SELECT extname FROM pg_extension WHERE extname = 'vector'", String.class);

        assertFalse(extensions.isEmpty(), "the 'vector' extension must be installed by V1__initial_schema.sql");
    }

    @Test
    void flywayMigrationRanSuccessfully() {
        List<String> appliedVersions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);

        assertTrue(appliedVersions.contains("1"), "V1__initial_schema.sql must have been applied by Flyway");
    }

    @Test
    void kafkaBrokerIsAccessible() throws Exception {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            DescribeClusterResult describeClusterResult = adminClient.describeCluster();
            Collection<Node> nodes = describeClusterResult.nodes().get(30, TimeUnit.SECONDS);

            assertFalse(nodes.isEmpty(), "the Kafka broker started by Testcontainers must report at least one node");
        }
    }
}

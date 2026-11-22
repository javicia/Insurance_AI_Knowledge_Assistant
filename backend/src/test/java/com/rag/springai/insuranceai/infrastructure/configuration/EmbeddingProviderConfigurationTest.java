package com.rag.springai.insuranceai.infrastructure.configuration;

import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties.EmbeddingProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consistency rule between this application's embedding provider and Spring AI's own
 * model-selection property (FASE 31).
 *
 * <p>Tested as a pure function rather than by booting an application context: the rule is the
 * valuable part, and a context-per-combination test would be slow enough that it would end up
 * covering one case instead of all of them.
 */
class EmbeddingProviderConfigurationTest {

    @Test
    void acceptsEveryRealProviderPairedWithItsOwnSpringAiSelection() {
        assertDoesNotThrow(() -> EmbeddingProviderConfiguration.verify(EmbeddingProvider.OPENAI, "openai"));
        assertDoesNotThrow(() -> EmbeddingProviderConfiguration.verify(EmbeddingProvider.OLLAMA, "ollama"));
    }

    @Test
    void theFakeProviderIsCompatibleWithAnySpringAiSelection() {
        // The fake adapter hashes in process and injects no EmbeddingModel, so it cannot be
        // mismatched against one. Constraining it was a real defect: PgVectorStoreAutoConfiguration
        // REQUIRES an EmbeddingModel bean to exist - even though this project queries pgvector by
        // plain JDBC and never calls PgVectorStore - so demanding `none` broke every integration
        // test with "No qualifying bean of type EmbeddingModel". A rule that forbids a legitimate
        // configuration is as much a bug as one that permits a dangerous one.
        assertDoesNotThrow(() -> EmbeddingProviderConfiguration.verify(EmbeddingProvider.FAKE, "openai"));
        assertDoesNotThrow(() -> EmbeddingProviderConfiguration.verify(EmbeddingProvider.FAKE, "ollama"));
        assertDoesNotThrow(() -> EmbeddingProviderConfiguration.verify(EmbeddingProvider.FAKE, null));
    }

    @Test
    void rejectsTheDangerousMismatchThatWouldMislabelVectors() {
        // The direction that does NOT fail on its own: the Ollama adapter would be created, would
        // inject the OpenAI EmbeddingModel, and would write OpenAI vectors tagged as Ollama -
        // corrupting the stored embedding identity and spending money on an API this project
        // forbids. Nothing downstream would notice.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EmbeddingProviderConfiguration.verify(EmbeddingProvider.OLLAMA, "openai"));

        assertTrue(e.getMessage().contains("embedding-provider=ollama"), e.getMessage());
        assertTrue(e.getMessage().contains("spring.ai.model.embedding=ollama"), e.getMessage());
    }

    @Test
    void rejectsTheOppositeMismatchToo() {
        assertThrows(IllegalStateException.class,
                () -> EmbeddingProviderConfiguration.verify(EmbeddingProvider.OPENAI, "ollama"));
    }

    @Test
    void reportsAnUnsetPropertyExplicitlyRatherThanAsEmptyText() {
        // An unset property is the most likely real mistake (adding embedding-provider but
        // forgetting the Spring AI selector), so the message has to name it as unset rather than
        // print an empty string the reader has to interpret.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EmbeddingProviderConfiguration.verify(EmbeddingProvider.OLLAMA, null));

        assertTrue(e.getMessage().contains("<unset>"), e.getMessage());
    }

    @Test
    void toleratesSurroundingWhitespaceAndCase() {
        assertDoesNotThrow(() -> EmbeddingProviderConfiguration.verify(EmbeddingProvider.OLLAMA, "  OLLAMA  "));
    }
}

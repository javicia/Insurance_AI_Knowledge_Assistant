package com.rag.springai.insuranceai.infrastructure.configuration;

import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties.EmbeddingProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Objects;

/**
 * Fails fast when the application's embedding provider and Spring AI's own model-selection
 * property disagree (FASE 31).
 *
 * <p>Both the OpenAI and the Ollama starters are on the classpath, and each autoconfigures an
 * {@code EmbeddingModel} bean. Spring AI resolves that with {@code spring.ai.model.embedding},
 * which decides <em>which</em> autoconfiguration runs. This application separately has
 * {@code insurance-ai.ai.embedding-provider}, which decides which <em>adapter</em> is created.
 *
 * <p>The two have to agree, and nothing in the framework enforces it. The failure mode when they
 * do not is genuinely confusing, and differs by direction:
 *
 * <ul>
 *   <li>{@code embedding-provider: ollama} but {@code spring.ai.model.embedding: openai} - the
 *       Ollama adapter is created, injects the OpenAI {@code EmbeddingModel}, and silently
 *       produces <b>OpenAI vectors labelled as Ollama</b>. Nothing fails. Vectors get written
 *       with a wrong identity, which is exactly the corruption {@code EmbeddingModelDescriptor}
 *       exists to prevent - and it would also spend money on an API this project forbids.</li>
 *   <li>{@code embedding-provider: openai} but {@code spring.ai.model.embedding: ollama} - the
 *       OpenAI adapter fails to find {@code spring.ai.openai.embedding.model} or gets the wrong
 *       bean, surfacing as an opaque startup error far from its cause.</li>
 * </ul>
 *
 * The first case is the dangerous one: it does not fail, it mislabels. Hence this check.
 *
 * <p>Runs on {@link ApplicationReadyEvent} rather than as a {@code @PostConstruct} on a bean, so
 * it sees the fully-resolved environment and cannot itself affect bean creation order. The
 * application has already started by then; it stops with a message naming both properties and
 * their values, which is what makes the misconfiguration self-explanatory.
 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingProviderConfiguration {

    static final String SPRING_AI_EMBEDDING_PROPERTY = "spring.ai.model.embedding";

    private final InsuranceAiProperties properties;
    private final Environment environment;

    public EmbeddingProviderConfiguration(InsuranceAiProperties properties, Environment environment) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    @EventListener(ApplicationReadyEvent.class)
    void verifyEmbeddingProviderIsConsistent() {
        EmbeddingProvider configured = properties.ai().embeddingProvider();
        String springAiSelection = environment.getProperty(SPRING_AI_EMBEDDING_PROPERTY, "");
        verify(configured, springAiSelection);
    }

    /**
     * Package-private and static so the rule can be unit tested without a Spring context - the
     * rule is the valuable part, and a test that had to boot an application to check it would be
     * slow enough that it would not cover every combination.
     */
    static void verify(EmbeddingProvider configured, String springAiSelection) {
        // Only the adapters that actually INJECT an EmbeddingModel can be mismatched against it.
        //
        // FAKE injects none - it hashes in process - so any Spring AI selection is harmless to
        // it. And it must not be constrained to `none`, because
        // `PgVectorStoreAutoConfiguration` requires an EmbeddingModel bean to exist even though
        // this project queries pgvector through plain JDBC and never calls PgVectorStore itself.
        // Forcing `none` broke every integration test with
        // "No qualifying bean of type EmbeddingModel" - a stricter rule that forbade a
        // legitimate configuration.
        if (configured == EmbeddingProvider.FAKE) {
            return;
        }
        String expected = switch (configured) {
            case OPENAI -> "openai";
            case OLLAMA -> "ollama";
            case FAKE -> throw new IllegalStateException("unreachable: handled above");
        };
        String actual = springAiSelection == null ? "" : springAiSelection.trim().toLowerCase(Locale.ROOT);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(("Inconsistent embedding configuration: "
                    + "insurance-ai.ai.embedding-provider=%s requires %s=%s, but it is '%s'. "
                    + "These two must agree: the first selects this application's adapter, the second selects "
                    + "which Spring AI EmbeddingModel is autoconfigured. A mismatch can silently produce vectors "
                    + "from one provider labelled as another, which corrupts the stored embedding identity.")
                    .formatted(configured.name().toLowerCase(Locale.ROOT), SPRING_AI_EMBEDDING_PROPERTY,
                            expected, actual.isEmpty() ? "<unset>" : actual));
        }
    }
}

package com.rag.springai.insuranceai.adapters.outbound.embeddings;

import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.SimilarityMetric;
import com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding generation backed by Spring AI's {@code EmbeddingModel}, configured for OpenAI
 * (see {@code spring.ai.openai.embedding.model} in {@code application.yaml}).
 *
 * <p>Active whenever the configured provider is not {@code fake} - unlike
 * {@code OpenAiLlmAdapter}/{@code AnthropicLlmAdapter} (which are mutually exclusive on
 * {@code insurance-ai.ai.provider}), embeddings always use this adapter for both
 * {@code openai} and {@code anthropic} providers, because Anthropic has no embeddings API.
 * This is a real technical constraint, documented in {@code docs/rag/EMBEDDINGS.md} - not a
 * design choice, and not hidden behind an illusion of full provider symmetry.
 *
 * <p><b>Observability (FASE 27, benchmarking):</b> each call is timed as {@code
 * rag.embedding.latency} tagged {@code provider="openai"}. Timed <em>here</em>, in the adapter,
 * rather than at {@code HybridRetrievalService}'s call site, because embeddings are also
 * generated during ingestion ({@code EmbedDocumentVersionUseCase}) - instrumenting the single
 * place that actually performs the call is the only way to cover both paths, and it keeps the
 * measurement scoped to the real network round trip rather than to surrounding orchestration.
 */
@Component
@ConditionalOnExpression("'${insurance-ai.ai.provider}' != 'fake'")
public class OpenAiEmbeddingModelAdapter implements EmbeddingModelPort {

    private final EmbeddingModel embeddingModel;
    private final String modelName;
    private final MeterRegistry meterRegistry;

    public OpenAiEmbeddingModelAdapter(EmbeddingModel embeddingModel,
            @Value("${spring.ai.openai.embedding.model}") String modelName, MeterRegistry meterRegistry) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public EmbeddingVector embed(String text) {
        Timer.Sample sample = Timer.start(meterRegistry);
        float[] values;
        try {
            values = embeddingModel.embed(text);
        }
        finally {
            // Recorded in a finally so a failed call still contributes its (usually long) latency:
            // a benchmark that silently drops timeouts would report an unrealistically fast p99.
            sample.stop(meterRegistry.timer("rag.embedding.latency", "provider", "openai"));
        }
        List<Float> boxed = new ArrayList<>(values.length);
        for (float value : values) {
            boxed.add(value);
        }
        return new EmbeddingVector(boxed);
    }

    @Override
    public EmbeddingModelDescriptor descriptor() {
        return new EmbeddingModelDescriptor("openai", modelName, null, embeddingModel.dimensions(),
                SimilarityMetric.COSINE);
    }
}

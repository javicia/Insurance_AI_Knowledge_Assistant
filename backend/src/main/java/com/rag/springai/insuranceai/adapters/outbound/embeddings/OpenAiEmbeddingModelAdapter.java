package com.rag.springai.insuranceai.adapters.outbound.embeddings;

import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.SimilarityMetric;
import com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort;
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
 */
@Component
@ConditionalOnExpression("'${insurance-ai.ai.provider}' != 'fake'")
public class OpenAiEmbeddingModelAdapter implements EmbeddingModelPort {

    private final EmbeddingModel embeddingModel;
    private final String modelName;

    public OpenAiEmbeddingModelAdapter(EmbeddingModel embeddingModel,
            @Value("${spring.ai.openai.embedding.model}") String modelName) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
    }

    @Override
    public EmbeddingVector embed(String text) {
        float[] values = embeddingModel.embed(text);
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

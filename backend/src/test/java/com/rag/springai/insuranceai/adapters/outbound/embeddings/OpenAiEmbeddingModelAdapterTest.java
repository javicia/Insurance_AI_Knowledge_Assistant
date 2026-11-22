package com.rag.springai.insuranceai.adapters.outbound.embeddings;

import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the adapter correctly delegates to and wraps Spring AI's {@code EmbeddingModel} -
 * no real OpenAI call is made (brief section 16: unit tested, not provider tested).
 */
class OpenAiEmbeddingModelAdapterTest {

    @Test
    void delegatesToTheUnderlyingEmbeddingModelAndWrapsTheResult() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("Water damage coverage")).thenReturn(new float[] { 0.1f, 0.2f, 0.3f });

        OpenAiEmbeddingModelAdapter adapter = new OpenAiEmbeddingModelAdapter(embeddingModel,
                "text-embedding-3-small", new SimpleMeterRegistry());

        EmbeddingVector vector = adapter.embed("Water damage coverage");

        assertEquals(3, vector.dimension());
        assertEquals(0.1f, vector.values().get(0));
    }

    @Test
    void descriptorReportsTheConfiguredModelNameAndTheModelsOwnDimensions() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(1536);

        OpenAiEmbeddingModelAdapter adapter = new OpenAiEmbeddingModelAdapter(embeddingModel,
                "text-embedding-3-small", new SimpleMeterRegistry());

        EmbeddingModelDescriptor descriptor = adapter.descriptor();

        assertEquals("openai", descriptor.provider());
        assertEquals("text-embedding-3-small", descriptor.model());
        assertEquals(1536, descriptor.dimensions());
    }
}

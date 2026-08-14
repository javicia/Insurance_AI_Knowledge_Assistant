package com.rag.springai.insuranceai.adapters.outbound.embeddings;

import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeEmbeddingModelAdapterTest {

    private final FakeEmbeddingModelAdapter adapter = new FakeEmbeddingModelAdapter(new SimpleMeterRegistry());

    @Test
    void producesVectorsMatchingTheDescriptorDimension() {
        EmbeddingVector vector = adapter.embed("Water damage coverage");

        assertEquals(adapter.descriptor().dimensions(), vector.dimension());
    }

    @Test
    void isDeterministicForIdenticalText() {
        EmbeddingVector first = adapter.embed("Water damage is covered up to the policy limit");
        EmbeddingVector second = adapter.embed("Water damage is covered up to the policy limit");

        assertEquals(first, second);
    }

    @Test
    void textSharingVocabularyIsMoreSimilarThanUnrelatedText() {
        EmbeddingVector question = adapter.embed("Is water damage covered by my policy?");
        EmbeddingVector relevantChunk = adapter
                .embed("Water damage caused by a burst pipe is covered up to the policy limit.");
        EmbeddingVector unrelatedChunk = adapter
                .embed("Claims must be reported within thirty days of the incident to customer service.");

        double similarityToRelevant = cosineSimilarity(question, relevantChunk);
        double similarityToUnrelated = cosineSimilarity(question, unrelatedChunk);

        assertTrue(similarityToRelevant > similarityToUnrelated,
                "expected the water-damage chunk to be more similar to the water-damage question than the claims chunk");
    }

    @Test
    void descriptorReportsTheFakeProviderExplicitly() {
        EmbeddingModelDescriptor descriptor = adapter.descriptor();

        assertEquals("fake", descriptor.provider());
    }

    private double cosineSimilarity(EmbeddingVector a, EmbeddingVector b) {
        double dot = 0;
        for (int i = 0; i < a.dimension(); i++) {
            dot += a.values().get(i) * b.values().get(i);
        }
        return dot;
    }
}

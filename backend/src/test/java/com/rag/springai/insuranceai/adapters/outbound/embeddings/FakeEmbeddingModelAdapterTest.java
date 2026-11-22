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

    // --- FASE 29: Spanish tokenization regression tests ---

    @Test
    void wordsEndingInIonDoNotAllCollapseIntoOneSharedToken() {
        // The damaging half of the pre-FASE-29 defect. Under [a-z0-9]+ every -ión word emitted
        // the same "n" fragment, so these three unrelated insurance terms shared a hash bucket
        // and were pushed together in vector space - a direct mechanism for a false semantic
        // match, on the one branch that term coverage cannot gate.
        EmbeddingVector collision = adapter.embed("colisión");
        EmbeddingVector exclusion = adapter.embed("exclusión");
        EmbeddingVector indemnity = adapter.embed("indemnización");

        assertEquals(0.0, cosineSimilarity(collision, exclusion), 1e-9,
                "distinct -ión terms must share no token");
        assertEquals(0.0, cosineSimilarity(collision, indemnity), 1e-9);
        assertEquals(0.0, cosineSimilarity(exclusion, indemnity), 1e-9);
    }

    @Test
    void anAccentedWordIsNotSplitIntoFragmentsSharedWithUnrelatedWords() {
        // "póliza" used to tokenize as ["p", "liza"] and "vehículo" as ["veh", "culo"]. Any two
        // accented words were liable to share a fragment; these two share none once each is a
        // whole token.
        assertEquals(0.0, cosineSimilarity(adapter.embed("póliza"), adapter.embed("vehículo")), 1e-9);
    }

    @Test
    void aSpanishQuestionIsMoreSimilarToItsAnswerThanToAnUnrelatedSpanishChunk() {
        // The English equivalent of this test already existed; this is the Spanish one, which is
        // the language the corpus is actually written in.
        EmbeddingVector question = adapter.embed("¿Qué daños por colisión cubre la póliza de todo riesgo?");
        EmbeddingVector relevant = adapter
                .embed("La póliza de todo riesgo cubre los daños por colisión del vehículo asegurado.");
        EmbeddingVector unrelated = adapter
                .embed("El siniestro debe comunicarse en el plazo de siete días a la compañía.");

        assertTrue(cosineSimilarity(question, relevant) > cosineSimilarity(question, unrelated),
                "the all-risk collision chunk must be closer to the collision question than the claims-deadline chunk");
    }

    @Test
    void theDescriptorVersionIdentifiesTheTokenizerGeneration() {
        // vector_store.metadata persists this per chunk, so a corpus embedded by the pre-FASE-29
        // tokenizer is identifiable. Without the bump, stale v1 vectors would be scored against
        // v2 query vectors and nothing would reveal the mismatch.
        assertEquals("v2", adapter.descriptor().modelVersion());
    }

    private double cosineSimilarity(EmbeddingVector a, EmbeddingVector b) {
        double dot = 0;
        for (int i = 0; i < a.dimension(); i++) {
            dot += a.values().get(i) * b.values().get(i);
        }
        return dot;
    }
}

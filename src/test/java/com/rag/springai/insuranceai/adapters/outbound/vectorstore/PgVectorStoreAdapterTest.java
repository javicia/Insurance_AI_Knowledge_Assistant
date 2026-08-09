package com.rag.springai.insuranceai.adapters.outbound.vectorstore;

import com.rag.springai.insuranceai.TestcontainersConfiguration;
import com.rag.springai.insuranceai.domain.document.ChunkContent;
import com.rag.springai.insuranceai.domain.document.ChunkIndex;
import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.RetrievedChunk;
import com.rag.springai.insuranceai.domain.rag.SimilarityMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test against a real PostgreSQL + pgvector instance (Testcontainers, brief
 * section 16). Uses hand-built 1536-dimension vectors with controllable cosine
 * relationships (orthogonal = maximally dissimilar, identical = maximally similar) rather than
 * a real embedding model, so similarity/threshold/top-K behaviour is exactly predictable.
 *
 * <p>{@code vector_store} is truncated before every test: the Spring context (and therefore
 * the underlying Testcontainers database) is shared and cached across test methods, and
 * several tests deliberately reuse the same vector so results would otherwise bleed between
 * tests.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PgVectorStoreAdapterTest {

    private static final int DIMENSIONS = 1536;

    @Autowired
    private PgVectorStoreAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearVectorStore() {
        jdbcTemplate.update("DELETE FROM public.vector_store");
    }

    private final EmbeddingModelDescriptor descriptor = new EmbeddingModelDescriptor("openai",
            "text-embedding-3-small", null, DIMENSIONS, SimilarityMetric.COSINE);

    private EmbeddingVector vector(float... firstValues) {
        List<Float> values = new ArrayList<>(DIMENSIONS);
        for (float value : firstValues) {
            values.add(value);
        }
        while (values.size() < DIMENSIONS) {
            values.add(0f);
        }
        return new EmbeddingVector(values);
    }

    private DocumentChunk chunk(String content, ChunkMetadata metadata) {
        return DocumentChunk.create(DocumentId.generate(), DocumentVersionId.generate(), new ChunkIndex(0),
                new ChunkContent(content), metadata);
    }

    @Test
    void retrievesAnIndexedChunkThatIsIdenticalToTheQuery() {
        DocumentChunk chunk = chunk("Water damage coverage", ChunkMetadata.empty());
        adapter.index(chunk, vector(1f, 0f, 0f), descriptor);

        List<RetrievedChunk> results = adapter.search(vector(1f, 0f, 0f), 8, 0.5);

        assertEquals(1, results.size());
        assertEquals(chunk.id(), results.get(0).chunkId());
        assertEquals("Water damage coverage", results.get(0).content());
        assertTrue(results.get(0).similarityScore() > 0.99);
    }

    @Test
    void rejectsAnOrthogonalChunkBelowTheSimilarityThreshold() {
        DocumentChunk relevant = chunk("relevant", ChunkMetadata.empty());
        DocumentChunk irrelevant = chunk("irrelevant", ChunkMetadata.empty());
        adapter.index(relevant, vector(1f, 0f, 0f), descriptor);
        adapter.index(irrelevant, vector(0f, 1f, 0f), descriptor);

        List<RetrievedChunk> results = adapter.search(vector(1f, 0f, 0f), 8, 0.75);

        assertEquals(1, results.size());
        assertEquals(relevant.id(), results.get(0).chunkId());
    }

    @Test
    void limitsResultsToTopK() {
        for (int i = 0; i < 5; i++) {
            adapter.index(chunk("chunk " + i, ChunkMetadata.empty()), vector(1f, 0f, 0f), descriptor);
        }

        List<RetrievedChunk> results = adapter.search(vector(1f, 0f, 0f), 3, 0.5);

        assertEquals(3, results.size());
    }

    @Test
    void propagatesStructuralMetadata() {
        ChunkMetadata metadata = new ChunkMetadata(37, "Chapter 7", "7.2 Water Damage Coverage", 4);
        DocumentChunk chunk = chunk("content", metadata);
        adapter.index(chunk, vector(1f, 0f, 0f), descriptor);

        RetrievedChunk result = adapter.search(vector(1f, 0f, 0f), 8, 0.5).get(0);

        assertEquals(37, result.metadata().page());
        assertEquals("Chapter 7", result.metadata().chapter());
        assertEquals("7.2 Water Damage Coverage", result.metadata().section());
        assertEquals(4, result.metadata().paragraph());
        assertEquals(chunk.documentId(), result.documentId());
        assertEquals(chunk.documentVersionId(), result.documentVersionId());
    }

    @Test
    void reindexingTheSameChunkIdUpsertsRatherThanDuplicates() {
        DocumentChunk chunk = chunk("original content", ChunkMetadata.empty());
        adapter.index(chunk, vector(1f, 0f, 0f), descriptor);
        adapter.index(chunk, vector(1f, 0f, 0f), descriptor);

        List<RetrievedChunk> results = adapter.search(vector(1f, 0f, 0f), 8, 0.5);

        assertEquals(1, results.size(), "re-indexing the same chunk id must replace, not duplicate, its vector");
    }
}

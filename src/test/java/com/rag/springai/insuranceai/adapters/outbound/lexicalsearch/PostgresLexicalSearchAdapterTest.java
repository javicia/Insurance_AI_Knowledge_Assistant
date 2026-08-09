package com.rag.springai.insuranceai.adapters.outbound.lexicalsearch;

import com.rag.springai.insuranceai.TestcontainersConfiguration;
import com.rag.springai.insuranceai.adapters.outbound.vectorstore.PgVectorStoreAdapter;
import com.rag.springai.insuranceai.domain.document.ChunkContent;
import com.rag.springai.insuranceai.domain.document.ChunkIndex;
import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.LexicalSearchResult;
import com.rag.springai.insuranceai.domain.rag.RetrievalFilter;
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
 * Integration test against real PostgreSQL full-text search (Testcontainers, brief FASE 6
 * section 36) - {@code tsvector}/{@code tsquery}/{@code ts_rank_cd} behaviour (stemming, ranking,
 * metadata filtering) must be proven against the real engine, never mocked.
 *
 * <p>Rows are written via {@link PgVectorStoreAdapter#index} (the only write path into {@code
 * vector_store}, FASE 5) so {@code content_tsv} is populated exactly as it would be in
 * production - this test never inserts rows by hand.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostgresLexicalSearchAdapterTest {

    private static final int DIMENSIONS = 1536;

    @Autowired
    private PostgresLexicalSearchAdapter adapter;

    @Autowired
    private PgVectorStoreAdapter vectorStoreAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final EmbeddingModelDescriptor descriptor = new EmbeddingModelDescriptor("openai",
            "text-embedding-3-small", null, DIMENSIONS, SimilarityMetric.COSINE);

    @BeforeEach
    void clearVectorStore() {
        jdbcTemplate.update("DELETE FROM public.vector_store");
    }

    private EmbeddingVector zeroVector() {
        List<Float> values = new ArrayList<>(DIMENSIONS);
        for (int i = 0; i < DIMENSIONS; i++) {
            values.add(0f);
        }
        return new EmbeddingVector(values);
    }

    private DocumentChunk chunk(String content, ChunkMetadata metadata) {
        return DocumentChunk.create(DocumentId.generate(), DocumentVersionId.generate(), new ChunkIndex(0),
                new ChunkContent(content), metadata);
    }

    private DocumentChunk index(String content, ChunkMetadata metadata, DocumentType type,
            DocumentClassification classification) {
        DocumentChunk chunk = chunk(content, metadata);
        vectorStoreAdapter.index(chunk, zeroVector(), descriptor, type, classification);
        return chunk;
    }

    private DocumentChunk index(String content, ChunkMetadata metadata) {
        return index(content, metadata, DocumentType.POLICY, DocumentClassification.INTERNAL);
    }

    @Test
    void findsAChunkByAnExactLexicalMatch() {
        DocumentChunk chunk = index("Water damage caused by a burst pipe is covered.", ChunkMetadata.empty());

        List<LexicalSearchResult> results = adapter.search("burst pipe", 8, RetrievalFilter.none());

        assertEquals(1, results.size());
        assertEquals(chunk.id(), results.get(0).chunkId());
    }

    @Test
    void findsAChunkByAStemmedPartialMatch() {
        // English text-search stemming reduces verb inflections to a shared stem ("covers" and
        // "covered" both stem to "cover") - proving this is real PostgreSQL FTS matching, not
        // literal substring matching. Verified empirically: to_tsvector('english', 'covers') and
        // to_tsquery('english', 'covered') both yield the 'cover' lexeme. A noun-derived form
        // like "coverage" is deliberately NOT used here - PostgreSQL's english stemmer keeps it
        // as a distinct 'coverag' stem, so it would not exercise this behaviour.
        DocumentChunk chunk = index("The policy covers water damage.", ChunkMetadata.empty());

        List<LexicalSearchResult> results = adapter.search("covered", 8, RetrievalFilter.none());

        assertEquals(1, results.size());
        assertEquals(chunk.id(), results.get(0).chunkId());
    }

    @Test
    void anIrrelevantQueryReturnsNoResults() {
        index("Water damage caused by a burst pipe is covered.", ChunkMetadata.empty());

        List<LexicalSearchResult> results = adapter.search("commercial airliner cruise altitude", 8,
                RetrievalFilter.none());

        assertTrue(results.isEmpty());
    }

    @Test
    void ranksAChunkWithMoreQueryTermOccurrencesHigher() {
        DocumentChunk stronger = index("Water damage. Water damage. Water damage everywhere.", ChunkMetadata.empty());
        index("Water damage is mentioned once here.", ChunkMetadata.empty());

        List<LexicalSearchResult> results = adapter.search("water damage", 8, RetrievalFilter.none());

        assertEquals(2, results.size());
        assertEquals(stronger.id(), results.get(0).chunkId());
        assertTrue(results.get(0).lexicalScore() > results.get(1).lexicalScore());
    }

    @Test
    void limitsResultsToTopK() {
        for (int i = 0; i < 5; i++) {
            index("Water damage chunk " + i, ChunkMetadata.empty());
        }

        List<LexicalSearchResult> results = adapter.search("water damage", 3, RetrievalFilter.none());

        assertEquals(3, results.size());
    }

    @Test
    void propagatesStructuralMetadata() {
        ChunkMetadata metadata = new ChunkMetadata(37, "Chapter 7", "7.2 Water Damage Coverage", 4);
        DocumentChunk chunk = index("Water damage content", metadata);

        LexicalSearchResult result = adapter.search("water damage", 8, RetrievalFilter.none()).get(0);

        assertEquals(37, result.metadata().page());
        assertEquals("Chapter 7", result.metadata().chapter());
        assertEquals("7.2 Water Damage Coverage", result.metadata().section());
        assertEquals(4, result.metadata().paragraph());
        assertEquals(chunk.documentId(), result.documentId());
        assertEquals(chunk.documentVersionId(), result.documentVersionId());
    }

    @Test
    void excludesChunksNotMatchingTheDocumentTypeFilter() {
        DocumentChunk policyChunk = index("Water damage policy clause.", ChunkMetadata.empty(), DocumentType.POLICY,
                DocumentClassification.INTERNAL);
        index("Water damage claims procedure.", ChunkMetadata.empty(), DocumentType.CLAIMS_PROCEDURE,
                DocumentClassification.INTERNAL);

        RetrievalFilter filter = new RetrievalFilter(null, null, DocumentType.POLICY, null, null, null);
        List<LexicalSearchResult> results = adapter.search("water damage", 8, filter);

        assertEquals(1, results.size());
        assertEquals(policyChunk.id(), results.get(0).chunkId());
    }

    @Test
    void excludesChunksNotMatchingTheDocumentClassificationFilter() {
        DocumentChunk internalChunk = index("Water damage internal note.", ChunkMetadata.empty(), DocumentType.POLICY,
                DocumentClassification.INTERNAL);
        index("Water damage confidential note.", ChunkMetadata.empty(), DocumentType.POLICY,
                DocumentClassification.CONFIDENTIAL);

        RetrievalFilter filter = new RetrievalFilter(null, null, null, DocumentClassification.INTERNAL, null, null);
        List<LexicalSearchResult> results = adapter.search("water damage", 8, filter);

        assertEquals(1, results.size());
        assertEquals(internalChunk.id(), results.get(0).chunkId());
    }
}

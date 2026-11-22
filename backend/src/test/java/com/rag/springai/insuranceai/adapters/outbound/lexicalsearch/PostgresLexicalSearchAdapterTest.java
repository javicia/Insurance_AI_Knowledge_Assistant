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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

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
 * production - this test never inserts rows by hand. {@code vector_store} is truncated before
 * every test by {@link com.rag.springai.insuranceai.DatabaseCleanupExtension}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(com.rag.springai.insuranceai.DatabaseCleanupExtension.class)
class PostgresLexicalSearchAdapterTest {

    private static final int DIMENSIONS = 1536;

    @Autowired
    private PostgresLexicalSearchAdapter adapter;

    @Autowired
    private PgVectorStoreAdapter vectorStoreAdapter;

    private final EmbeddingModelDescriptor descriptor = new EmbeddingModelDescriptor("openai",
            "text-embedding-3-small", null, DIMENSIONS, SimilarityMetric.COSINE);

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
        // Asserted in Spanish since FASE 28: the production configuration is spanish_unaccent
        // (V8__spanish_full_text_search.sql), so an English stemming assertion would be testing
        // a configuration this project deliberately no longer has. The property under test is
        // unchanged - that this is real PostgreSQL FTS stemming rather than substring matching.
        // Verified empirically against pgvector/pgvector:pg16: "cubre", "cubren" and "cubrir" all
        // yield the 'cubr' lexeme under spanish_unaccent. ("cubierto"/"cubierta" deliberately
        // avoided - Snowball keeps the irregular participle as a distinct 'cubiert' stem, so it
        // would not exercise this behaviour. See docs/rag/AUDITORIA_RAG_ES.md.)
        DocumentChunk chunk = index("La póliza cubre los daños por agua.", ChunkMetadata.empty());

        List<LexicalSearchResult> results = adapter.search("cubrir", 8, RetrievalFilter.none());

        assertEquals(1, results.size());
        assertEquals(chunk.id(), results.get(0).chunkId());
    }

    @Test
    void findsAChunkWhenTheQuestionIsTypedWithoutAccents() {
        // The real reason spanish_unaccent copies 'spanish' and adds the unaccent dictionary
        // rather than just using 'spanish': employees type questions without accents, and the
        // corpus is correctly accented. Under the previous 'english' configuration neither
        // stemming nor accent folding applied, so this question matched nothing at all.
        DocumentChunk chunk = index("La póliza cubre los daños del vehículo asegurado.", ChunkMetadata.empty());

        List<LexicalSearchResult> results = adapter.search("¿que danos del vehiculo cubre la poliza?", 8,
                RetrievalFilter.none());

        assertEquals(1, results.size());
        assertEquals(chunk.id(), results.get(0).chunkId());
    }

    @Test
    void matchesAMultiTermSpanishQuestionWhereOnlySomeTermsArePresent() {
        // The FASE 28 regression test for the ~3.6% lexical contribution rate. Under the previous
        // websearch_to_tsquery AND-semantics, EVERY lexeme of this question had to appear in one
        // chunk, so a natural-language question never matched - "granizo" alone being absent was
        // enough to return nothing. OR-semantics is what makes the lexical branch contribute
        // candidates at all; term coverage (asserted below) is what keeps that from loosening
        // grounding.
        DocumentChunk chunk = index("La póliza de todo riesgo cubre los daños por granizo.", ChunkMetadata.empty());

        List<LexicalSearchResult> results = adapter.search("¿Cubre la póliza de todo riesgo los daños por pedrisco?",
                8, RetrievalFilter.none());

        assertEquals(1, results.size());
        assertEquals(chunk.id(), results.get(0).chunkId());
    }

    @Test
    void reportsHighTermCoverageForAQuestionTheChunkActuallyAnswers() {
        index("La franquicia aplicable en la cobertura de lunas es de 100 euros por siniestro.",
                ChunkMetadata.empty());

        LexicalSearchResult result = adapter
                .search("¿Cuál es la franquicia de la cobertura de lunas?", 8, RetrievalFilter.none())
                .get(0);

        assertEquals(result.queryTermCount(), result.matchedTermCount(),
                "every lexeme of an in-corpus question should be present in the chunk that answers it");
        assertEquals(1.0, result.termCoverage(), 0.0001);
    }

    @Test
    void reportsLowTermCoverageForAnOutOfCorpusQuestionThatStillMatchesLexically() {
        // The defect that made the previous attempt at this fix unsafe, captured as a test. This
        // chunk is retrieved (it shares "cubrir" and "seguro" with the question) and earns a
        // ts_rank_cd far above insurance-ai.rag.lexical.min-rank, so the pre-FASE-28 grounding
        // rule - any lexical match clearing min-rank - would have answered a HOME insurance
        // question from an AUTO policy. Coverage is the signal that separates them, which is why
        // AskInsuranceKnowledgeUseCase gates grounding on it and not on the score.
        index("El seguro de auto cubre la responsabilidad civil obligatoria.", ChunkMetadata.empty());

        List<LexicalSearchResult> results = adapter.search("¿Cubre mi seguro de hogar las inundaciones del sótano?", 8,
                RetrievalFilter.none());

        assertEquals(1, results.size(), "the chunk is still retrieved - candidate generation is deliberately liberal");
        LexicalSearchResult result = results.get(0);
        assertTrue(result.lexicalScore() > 0.05,
                "ts_rank_cd clears min-rank, so rank alone cannot reject this: " + result.lexicalScore());
        assertTrue(result.termCoverage() < 0.6,
                "coverage must stay below min-term-coverage so grounding rejects it: " + result.termCoverage());
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

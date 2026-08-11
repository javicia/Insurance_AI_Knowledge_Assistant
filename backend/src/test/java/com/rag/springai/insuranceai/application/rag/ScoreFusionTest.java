package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunkId;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;
import com.rag.springai.insuranceai.domain.rag.LexicalSearchResult;
import com.rag.springai.insuranceai.domain.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreFusionTest {

    private static final double RRF_K = 60.0;

    private final ScoreFusion scoreFusion = new ScoreFusion();
    private final DocumentId documentId = DocumentId.generate();
    private final DocumentVersionId documentVersionId = DocumentVersionId.generate();

    private DocumentChunkId chunkId(String suffix) {
        return new DocumentChunkId(UUID.nameUUIDFromBytes(suffix.getBytes()));
    }

    private RetrievedChunk semanticResult(String chunkIdSuffix, double score) {
        return new RetrievedChunk(chunkId(chunkIdSuffix), documentId, documentVersionId, "content " + chunkIdSuffix,
                ChunkMetadata.empty(), score);
    }

    private LexicalSearchResult lexicalResult(String chunkIdSuffix, double score) {
        return new LexicalSearchResult(chunkId(chunkIdSuffix), documentId, documentVersionId,
                "content " + chunkIdSuffix, ChunkMetadata.empty(), score);
    }

    @Test
    void aChunkFoundByBothBranchesSumsBothRankContributions() {
        List<RetrievedChunk> semantic = List.of(semanticResult("a", 0.9));
        List<LexicalSearchResult> lexical = List.of(lexicalResult("a", 0.5));

        List<HybridRetrievalResult> fused = scoreFusion.fuse(semantic, lexical, RRF_K);

        assertEquals(1, fused.size());
        HybridRetrievalResult result = fused.get(0);
        assertEquals(0.9, result.semanticScore());
        assertEquals(1, result.semanticRank());
        assertEquals(0.5, result.lexicalScore());
        assertEquals(1, result.lexicalRank());
        double expected = 1.0 / (RRF_K + 1) + 1.0 / (RRF_K + 1);
        assertEquals(expected, result.fusionScore(), 1e-9);
    }

    @Test
    void aChunkFoundOnlyBySemanticSearchHasNullLexicalFieldsAndAPartialScore() {
        List<RetrievedChunk> semantic = List.of(semanticResult("a", 0.9));

        List<HybridRetrievalResult> fused = scoreFusion.fuse(semantic, List.of(), RRF_K);

        HybridRetrievalResult result = fused.get(0);
        assertNull(result.lexicalScore());
        assertNull(result.lexicalRank());
        assertEquals(1.0 / (RRF_K + 1), result.fusionScore(), 1e-9);
    }

    @Test
    void aChunkFoundOnlyByLexicalSearchHasNullSemanticFieldsAndAPartialScore() {
        List<LexicalSearchResult> lexical = List.of(lexicalResult("a", 0.5));

        List<HybridRetrievalResult> fused = scoreFusion.fuse(List.of(), lexical, RRF_K);

        HybridRetrievalResult result = fused.get(0);
        assertNull(result.semanticScore());
        assertNull(result.semanticRank());
        assertEquals(1.0 / (RRF_K + 1), result.fusionScore(), 1e-9);
    }

    @Test
    void resultsAreOrderedByDescendingFusionScore() {
        List<RetrievedChunk> semantic = List.of(semanticResult("low", 0.6), semanticResult("high", 0.99));
        List<LexicalSearchResult> lexical = List.of(lexicalResult("high", 0.9));

        List<HybridRetrievalResult> fused = scoreFusion.fuse(semantic, lexical, RRF_K);

        assertTrue(fused.get(0).fusionScore() >= fused.get(1).fusionScore());
        assertEquals(chunkId("high"), fused.get(0).chunkId());
    }

    @Test
    void tiedFusionScoresBreakDeterministicallyByChunkId() {
        // A rank-1 semantic-only candidate and a rank-1 lexical-only candidate (different
        // chunks, no overlap) both contribute exactly 1/(k+1) - a genuine tie in fusionScore.
        List<RetrievedChunk> semantic = List.of(semanticResult("alpha", 0.9));
        List<LexicalSearchResult> lexical = List.of(lexicalResult("beta", 0.5));

        List<HybridRetrievalResult> fused = scoreFusion.fuse(semantic, lexical, RRF_K);

        assertEquals(2, fused.size());
        assertEquals(fused.get(0).fusionScore(), fused.get(1).fusionScore(), 1e-9, "both candidates must tie");
        String first = fused.get(0).chunkId().toString();
        String second = fused.get(1).chunkId().toString();
        assertTrue(first.compareTo(second) < 0,
                "tied candidates must be ordered by ascending chunkId string as a deterministic tie-break");
    }
}

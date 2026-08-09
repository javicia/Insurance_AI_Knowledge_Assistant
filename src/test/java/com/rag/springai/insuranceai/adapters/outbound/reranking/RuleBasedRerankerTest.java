package com.rag.springai.insuranceai.adapters.outbound.reranking;

import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunkId;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedRerankerTest {

    private final RuleBasedReranker reranker = new RuleBasedReranker();
    private final DocumentId documentId = DocumentId.generate();
    private final DocumentVersionId documentVersionId = DocumentVersionId.generate();

    private HybridRetrievalResult candidate(String suffix, String content, double fusionScore) {
        DocumentChunkId chunkId = new DocumentChunkId(UUID.nameUUIDFromBytes(suffix.getBytes()));
        return new HybridRetrievalResult(chunkId, documentId, documentVersionId, content, ChunkMetadata.empty(), 0.8,
                1, null, null, fusionScore, null);
    }

    @Test
    void everyReturnedCandidateHasANonNullRerankerScore() {
        List<HybridRetrievalResult> candidates = List.of(candidate("a", "water damage coverage", 0.5));

        List<HybridRetrievalResult> reranked = reranker.rerank("water damage", candidates, 8);

        assertEquals(1, reranked.size());
        assertTrue(reranked.get(0).rerankerScore() != null);
    }

    @Test
    void preservesSemanticLexicalAndFusionScoresUnchanged() {
        HybridRetrievalResult original = candidate("a", "water damage coverage", 0.5);

        HybridRetrievalResult reranked = reranker.rerank("water damage", List.of(original), 8).get(0);

        assertEquals(original.semanticScore(), reranked.semanticScore());
        assertEquals(original.semanticRank(), reranked.semanticRank());
        assertEquals(original.lexicalScore(), reranked.lexicalScore());
        assertEquals(original.lexicalRank(), reranked.lexicalRank());
        assertEquals(original.fusionScore(), reranked.fusionScore());
    }

    @Test
    void higherKeywordCoverageCanOvertakeAHigherFusionScore() {
        // "off-topic" has a slightly higher starting fusionScore but shares no words with the
        // question; "on-topic" starts slightly lower but literally contains every question
        // keyword, so its full-coverage boost (weight 0.1) outweighs the small 0.05 gap.
        HybridRetrievalResult offTopic = candidate("off", "exclusions apply for gradual leaks", 0.55);
        HybridRetrievalResult onTopic = candidate("on", "water damage from a burst pipe is covered", 0.5);

        List<HybridRetrievalResult> reranked = reranker.rerank("water damage burst pipe",
                List.of(offTopic, onTopic), 8);

        assertEquals(onTopic.chunkId(), reranked.get(0).chunkId(),
                "full keyword coverage should be able to overtake a purely fusion-ranked result");
    }

    @Test
    void limitsResultsToFinalTopK() {
        List<HybridRetrievalResult> candidates = List.of(candidate("a", "water", 0.9), candidate("b", "damage", 0.8),
                candidate("c", "coverage", 0.7));

        List<HybridRetrievalResult> reranked = reranker.rerank("water damage coverage", candidates, 2);

        assertEquals(2, reranked.size());
    }

    @Test
    void anEmptyCandidateListReturnsEmpty() {
        List<HybridRetrievalResult> reranked = reranker.rerank("question", List.of(), 8);

        assertTrue(reranked.isEmpty());
    }

    @Test
    void isDeterministicForTheSameInput() {
        List<HybridRetrievalResult> candidates = List.of(candidate("a", "water damage", 0.6),
                candidate("b", "fire damage", 0.6));

        List<HybridRetrievalResult> first = reranker.rerank("water damage", candidates, 8);
        List<HybridRetrievalResult> second = reranker.rerank("water damage", candidates, 8);

        assertEquals(first.stream().map(HybridRetrievalResult::chunkId).toList(),
                second.stream().map(HybridRetrievalResult::chunkId).toList());
    }
}

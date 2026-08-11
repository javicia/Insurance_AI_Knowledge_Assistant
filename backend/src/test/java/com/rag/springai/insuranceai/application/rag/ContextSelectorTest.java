package com.rag.springai.insuranceai.application.rag;

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

class ContextSelectorTest {

    private final ContextSelector contextSelector = new ContextSelector();
    private final DocumentId documentId = DocumentId.generate();
    private final DocumentVersionId documentVersionId = DocumentVersionId.generate();

    private HybridRetrievalResult candidate(String suffix, String content, String section) {
        DocumentChunkId chunkId = new DocumentChunkId(UUID.nameUUIDFromBytes(suffix.getBytes()));
        ChunkMetadata metadata = new ChunkMetadata(1, null, section, 1);
        return new HybridRetrievalResult(chunkId, documentId, documentVersionId, content, metadata, 0.9, 1, null,
                null, 0.5, null);
    }

    @Test
    void alwaysIncludesAtLeastOneCandidateEvenIfItAloneExceedsTheBudget() {
        HybridRetrievalResult huge = candidate("a", "x".repeat(10_000), "1");

        List<HybridRetrievalResult> selected = contextSelector.select(List.of(huge), 100);

        assertEquals(1, selected.size(), "a single oversized chunk must still be returned, not dropped entirely");
    }

    @Test
    void stopsAddingFurtherChunksOnceTheCharacterBudgetIsExceeded() {
        HybridRetrievalResult first = candidate("a", "x".repeat(50), "1");
        HybridRetrievalResult second = candidate("b", "y".repeat(50), "2");
        HybridRetrievalResult third = candidate("c", "z".repeat(50), "3");

        List<HybridRetrievalResult> selected = contextSelector.select(List.of(first, second, third), 80);

        assertEquals(1, selected.size(), "second chunk would push total past the 80-character budget");
    }

    @Test
    void deduplicatesByChunkId() {
        HybridRetrievalResult candidate = candidate("a", "water damage", "1");

        List<HybridRetrievalResult> selected = contextSelector.select(List.of(candidate, candidate), 10_000);

        assertEquals(1, selected.size());
    }

    @Test
    void capsChunksPerDocumentSectionForMinimalDiversity() {
        List<HybridRetrievalResult> sameSectionCandidates = List.of(candidate("a", "one", "7.2"),
                candidate("b", "two", "7.2"), candidate("c", "three", "7.2"), candidate("d", "four", "7.2"));

        List<HybridRetrievalResult> selected = contextSelector.select(sameSectionCandidates, 10_000);

        assertEquals(3, selected.size(), "at most 3 chunks from the same (document, section) pair");
    }

    @Test
    void preservesRankedOrderAmongSelectedCandidates() {
        HybridRetrievalResult first = candidate("a", "one", "1");
        HybridRetrievalResult second = candidate("b", "two", "2");

        List<HybridRetrievalResult> selected = contextSelector.select(List.of(first, second), 10_000);

        assertEquals(List.of(first.chunkId(), second.chunkId()),
                selected.stream().map(HybridRetrievalResult::chunkId).toList());
    }

    @Test
    void anEmptyCandidateListReturnsEmpty() {
        List<HybridRetrievalResult> selected = contextSelector.select(List.of(), 10_000);

        assertTrue(selected.isEmpty());
    }
}

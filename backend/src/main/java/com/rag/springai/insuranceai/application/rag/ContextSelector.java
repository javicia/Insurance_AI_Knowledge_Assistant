package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.document.DocumentChunkId;
import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the final, LLM-bound context from a ranked candidate list (brief FASE 6 section 17/18):
 * deduplicates by {@code chunkId}, caps chunks per (document, section) pair for a minimal
 * diversity guarantee, and enforces a character budget. Kept as its own class, separate from
 * retrieval/fusion/reranking and from prompt construction/LLM invocation (section 17's explicit
 * requirement not to mix these responsibilities into one class).
 *
 * <p>{@code maxCharacters} is a PoC approximation of a context window budget - a character
 * count, not real token counting (brief section 18) - see {@code docs/rag/HYBRID_SEARCH.md}.
 * {@code MAX_CHUNKS_PER_SECTION} is a simple document-diversity cap, not full Maximal Marginal
 * Relevance (brief section 20 explicitly allows deferring MMR).
 */
@Component
public class ContextSelector {

    private static final int MAX_CHUNKS_PER_SECTION = 3;

    public List<HybridRetrievalResult> select(List<HybridRetrievalResult> rankedCandidates, int maxCharacters) {
        List<HybridRetrievalResult> selected = new ArrayList<>();
        Set<DocumentChunkId> seenChunkIds = new HashSet<>();
        Map<String, Integer> chunksPerSection = new HashMap<>();
        int totalCharacters = 0;

        for (HybridRetrievalResult candidate : rankedCandidates) {
            if (!seenChunkIds.add(candidate.chunkId())) {
                continue;
            }

            String sectionKey = candidate.documentId() + "|" + candidate.metadata().section();
            int chunksAlreadyTaken = chunksPerSection.getOrDefault(sectionKey, 0);
            if (chunksAlreadyTaken >= MAX_CHUNKS_PER_SECTION) {
                continue;
            }

            int contentLength = candidate.content().length();
            if (!selected.isEmpty() && totalCharacters + contentLength > maxCharacters) {
                continue;
            }

            selected.add(candidate);
            chunksPerSection.put(sectionKey, chunksAlreadyTaken + 1);
            totalCharacters += contentLength;
        }

        return selected;
    }
}

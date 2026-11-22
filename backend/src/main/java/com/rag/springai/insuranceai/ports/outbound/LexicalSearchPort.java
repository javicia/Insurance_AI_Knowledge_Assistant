package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.rag.LexicalSearchResult;
import com.rag.springai.insuranceai.domain.rag.RetrievalFilter;

import java.util.List;

/**
 * Outbound port for lexical (keyword/BM25-style) retrieval (brief FASE 6 section 1/3) -
 * {@code VectorSearchPort}'s counterpart for full-text rather than semantic matching.
 * Implemented by {@code PostgresLexicalSearchAdapter} against {@code vector_store}'s generated
 * {@code tsvector} column ({@code V4__hybrid_search.sql}), never referenced from {@code domain}
 * or {@code application} beyond this interface.
 *
 * <p>Unlike {@link VectorSearchPort#search}, there is no separate relevance threshold parameter:
 * PostgreSQL's {@code @@} text-search match operator is itself the relevance gate - a row is
 * either returned (it matched the query) or it is not, so an empty result already means
 * "no lexical match", the same semantics {@code VectorSearchPort} expresses via its threshold.
 */
public interface LexicalSearchPort {

    /**
     * Returns at most {@code topK} chunks matching {@code query} (already query-expanded by the
     * caller if enabled - see {@code QueryExpander}), ordered by decreasing {@code ts_rank_cd}.
     * {@code filter} restricts by document metadata (brief section 6) - {@link
     * RetrievalFilter#none()} applies no restriction.
     */
    List<LexicalSearchResult> search(String query, int topK, RetrievalFilter filter);
}

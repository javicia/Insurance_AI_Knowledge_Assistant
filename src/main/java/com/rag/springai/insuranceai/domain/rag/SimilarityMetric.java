package com.rag.springai.insuranceai.domain.rag;

/**
 * The distance function used to rank vectors by similarity. {@code COSINE} is what pgvector
 * (via Spring AI's {@code PgVectorStore}) uses by default and is what
 * {@code docs/rag/EMBEDDINGS.md} documents as the metric this PoC uses.
 */
public enum SimilarityMetric {
    COSINE,
    EUCLIDEAN,
    DOT_PRODUCT
}

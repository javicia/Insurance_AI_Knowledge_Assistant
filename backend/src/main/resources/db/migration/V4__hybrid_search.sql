-- Hybrid search schema (FASE 6, Advanced RAG). Adds PostgreSQL full-text search to vector_store
-- rather than introducing a separate lexical index/table or external search infrastructure
-- (Elasticsearch/OpenSearch) - see docs/adr/ADR-006-ADVANCED-RAG-RETRIEVAL-STRATEGY.md. Flyway
-- remains the sole schema owner (spring.ai.vectorstore.pgvector.initialize-schema: false is
-- untouched).
--
-- Indexed content: chunk content (weight A, primary signal) and the chunk's section heading
-- from metadata (weight B, secondary signal) - see brief section 5 and docs/rag/HYBRID_SEARCH.md
-- for why 'english' text search configuration and this weighting were chosen, and why they are
-- explicitly a PoC starting point, not a scientifically tuned ranking function.
--
-- GENERATED ALWAYS ... STORED keeps the tsvector automatically in sync with content/metadata on
-- every insert/update - PgVectorStoreAdapter's existing ON CONFLICT upsert (FASE 5) needs no
-- changes to keep this column correct.
ALTER TABLE public.vector_store
    ADD COLUMN IF NOT EXISTS content_tsv tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(content, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(metadata ->> 'section', '')), 'B')
    ) STORED;

CREATE INDEX IF NOT EXISTS vector_store_content_tsv_gin_index
    ON public.vector_store USING gin (content_tsv);

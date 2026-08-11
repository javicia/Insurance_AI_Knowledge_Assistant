-- Vector store schema (FASE 5). Flyway is the sole owner of this schema
-- (spring.ai.vectorstore.pgvector.initialize-schema: false in application.yaml) - Spring AI's
-- PgVectorStore never creates or alters it itself (brief section 15/36).
--
-- Column shape and the "spring_ai_vector_index" HNSW/cosine index deliberately match exactly
-- what org.springframework.ai.vectorstore.pgvector.PgVectorStore would create with its default
-- builder settings (UUID id, COSINE_DISTANCE, HNSW index), so the table stays compatible with
-- that class's own SQL conventions even though PgVectorStoreAdapter talks to it via plain JDBC
-- (see docs/rag/RAG_DESIGN.md for why).
--
-- This is a derived/denormalized projection of document_chunks, not a new source of truth -
-- see docs/adr/ADR-005-EMBEDDING-AS-DERIVED-PROJECTION.md. "id" below is a DocumentChunk's own
-- id (see PgVectorStoreAdapter), not a new identity; the row can be fully rebuilt at any time
-- by re-running the FASE 5 embedding step against document_chunks.
--
-- Embedding dimension (1536) matches the configured spring.ai.openai.embedding.model
-- (text-embedding-3-small) - see docs/rag/EMBEDDINGS.md. Changing that model to one with a
-- different dimension requires a new migration recreating this column (pgvector columns have a
-- fixed width) - see the reindexing strategy documented there and in ADR-005.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS public.vector_store (
    id          uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content     text,
    metadata    json,
    embedding   vector(1536)
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON public.vector_store USING hnsw (embedding vector_cosine_ops);

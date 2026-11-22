-- Spanish lexical retrieval (FASE 28). V4__hybrid_search.sql indexed content_tsv with the
-- 'english' text search configuration while the entire corpus is Spanish insurance
-- documentation. Measured consequence: the lexical branch contributed candidates in ~3.6% of
-- retrievals, so hybrid search was semantic-only in practice.
--
-- 'english' on Spanish text fails in two compounding ways:
--   1. No Spanish stemming - 'lunas'/'luna', 'danos'/'dano', 'cubre'/'cubrir' never unify.
--   2. Spanish stopwords are KEPT as searchable lexemes ('la', 'de', 'los', 'por', 'en'),
--      because the english stopword list does not contain them. Under the AND-semantics
--      websearch_to_tsquery the adapter used, every one of those had to appear in a single
--      chunk for it to match at all.
--
-- spanish_unaccent additionally folds accents and 'n-tilde' via the unaccent dictionary, so a
-- question typed without accents ('poliza', 'danos') matches a correctly accented corpus. This
-- is a real usage pattern, not a hypothetical: measured 5/5 term coverage for an unaccented
-- question against accented source text.
--
-- A custom configuration is required rather than calling unaccent() inline: unaccent() is
-- STABLE, not IMMUTABLE, so it cannot appear in a GENERATED ALWAYS expression. to_tsvector
-- (regconfig, text) IS immutable regardless of the dictionaries the configuration names, which
-- is what makes the generated column below legal.
CREATE EXTENSION IF NOT EXISTS unaccent;

DROP TEXT SEARCH CONFIGURATION IF EXISTS spanish_unaccent;
CREATE TEXT SEARCH CONFIGURATION spanish_unaccent ( COPY = spanish );
ALTER TEXT SEARCH CONFIGURATION spanish_unaccent
    ALTER MAPPING FOR hword, hword_part, word WITH unaccent, spanish_stem;

-- PostgreSQL 16 cannot alter a generated column's expression, so the column is dropped and
-- re-added. Re-adding recomputes content_tsv for every existing row from the content/metadata
-- already stored, so no re-ingestion, re-chunking or re-embedding is needed - embeddings live
-- in a separate column and are untouched. Dropping the column also drops the dependent GIN
-- index, which is why it is recreated below.
ALTER TABLE public.vector_store DROP COLUMN IF EXISTS content_tsv;

ALTER TABLE public.vector_store
    ADD COLUMN content_tsv tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('spanish_unaccent', coalesce(content, '')), 'A') ||
        setweight(to_tsvector('spanish_unaccent', coalesce(metadata ->> 'section', '')), 'B')
    ) STORED;

CREATE INDEX IF NOT EXISTS vector_store_content_tsv_gin_index
    ON public.vector_store USING gin (content_tsv);

-- FASE 31: almacenamiento vectorial PARALELO para embeddings locales bge-m3 (1024 dimensiones).
--
-- Por que una tabla nueva y no una columna nueva ni un ALTER de la existente:
--
--   1. `vector_store.embedding` es `vector(1536)` y lo crea V3__vector_store.sql. Editar V3
--      cambiaria su checksum y Flyway invalidaria el historial de la base ya desplegada.
--   2. pgvector fija la dimension en la definicion de la columna, asi que 1024 y 1536 no pueden
--      convivir en la misma columna.
--   3. El enunciado exige almacenamiento paralelo y prohibe sobrescribir los embeddings
--      originales. Esta migracion no toca ni una fila de `vector_store`: los 2.037 vectores
--      fake v1 siguen intactos y siguen siendo la fuente del despliegue principal mientras
--      `insurance-ai.rag.vector-table` no se cambie.
--
-- Es ADITIVA y REVERSIBLE: en el despliegue principal crea una tabla vacia que nadie consulta
-- (el valor por defecto de `insurance-ai.rag.vector-table` sigue siendo `vector_store`), y el
-- rollback es `DROP TABLE public.vector_store_bge_m3`, que no pierde ningun dato original.
--
-- La estructura replica exactamente la de `vector_store` salvo la anchura del vector, de modo que
-- PgVectorStoreAdapter funciona contra cualquiera de las dos sin ramificar su SQL.
CREATE TABLE IF NOT EXISTS public.vector_store_bge_m3 (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content   text,
    metadata  json,
    embedding vector(1024)
);

-- Mismo tipo de indice y misma metrica que V3 (HNSW coseno): comparar dos configuraciones de
-- recuperacion con estructuras de indice distintas mediria el indice, no el modelo.
CREATE INDEX IF NOT EXISTS vector_store_bge_m3_hnsw_index
    ON public.vector_store_bge_m3 USING hnsw (embedding vector_cosine_ops);

-- Mismo indice FTS espanol que V8 anadio a `vector_store`, con identica configuracion
-- `spanish_unaccent`: la rama lexica debe comportarse igual en las dos tablas para que una
-- comparacion hibrida aisle el efecto del modelo de embeddings y nada mas.
ALTER TABLE public.vector_store_bge_m3
    ADD COLUMN IF NOT EXISTS content_tsv tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('spanish_unaccent', coalesce(content, '')), 'A') ||
        setweight(to_tsvector('spanish_unaccent', coalesce(metadata ->> 'section', '')), 'B')
    ) STORED;

CREATE INDEX IF NOT EXISTS vector_store_bge_m3_content_tsv_gin_index
    ON public.vector_store_bge_m3 USING gin (content_tsv);

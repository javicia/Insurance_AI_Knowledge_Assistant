-- Purely technical bootstrap migration (FASE 2 - Local Infrastructure).
-- Enables the pgvector extension so that later phases (starting FASE 5, see
-- V3__vector_store.sql) can create the actual vector_store table. No business or
-- bounded-context tables are created here: those belong to their own migrations, owned by
-- the phase that introduces the corresponding aggregate (see docs/adr and
-- PROJECT_DISCOVERY.md delivery plan).
CREATE EXTENSION IF NOT EXISTS vector;

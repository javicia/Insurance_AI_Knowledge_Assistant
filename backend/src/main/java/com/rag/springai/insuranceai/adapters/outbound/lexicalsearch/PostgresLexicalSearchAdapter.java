package com.rag.springai.insuranceai.adapters.outbound.lexicalsearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.springai.insuranceai.adapters.shared.persistence.RetrievalFilterSql;
import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunkId;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.rag.LexicalSearchResult;
import com.rag.springai.insuranceai.domain.rag.RetrievalFilter;
import com.rag.springai.insuranceai.ports.outbound.LexicalSearchPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link LexicalSearchPort} backed by PostgreSQL full-text search over {@code vector_store}'s
 * generated {@code content_tsv} column ({@code V4__hybrid_search.sql}) - reuses the same table
 * {@code PgVectorStoreAdapter} writes to, so a chunk's semantic and lexical representations are
 * always in sync by construction (there is only ever one write path, {@code
 * PgVectorStoreAdapter#index}). See {@code docs/rag/HYBRID_SEARCH.md} and
 * {@code docs/adr/ADR-006-ADVANCED-RAG-RETRIEVAL-STRATEGY.md} for why PostgreSQL FTS was chosen
 * over introducing Elasticsearch/OpenSearch for this PoC.
 *
 * <p>{@code websearch_to_tsquery} is used (not raw {@code to_tsquery}) because it accepts and
 * sanitizes free-form natural-language input - including the {@code " OR "}-joined query
 * expansion terms {@code QueryExpander} may add - without the caller needing to hand-escape
 * {@code tsquery} operator syntax; malformed input degrades to a query matching fewer/no rows
 * rather than throwing.
 */
@Component
public class PostgresLexicalSearchAdapter implements LexicalSearchPort {

    private static final String TABLE_NAME = "public.vector_store";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostgresLexicalSearchAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<LexicalSearchResult> search(String query, int topK, RetrievalFilter filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, content, metadata, ts_rank_cd(content_tsv, query) AS lexical_rank
                FROM %s, websearch_to_tsquery('english', ?) query
                WHERE content_tsv @@ query
                """.formatted(TABLE_NAME));
        List<Object> params = new ArrayList<>();
        params.add(query);
        RetrievalFilterSql.appendConditions(sql, params, filter);
        sql.append(" ORDER BY lexical_rank DESC LIMIT ?");
        params.add(topK);

        return jdbcTemplate.query(sql.toString(), this::mapRow, params.toArray());
    }

    private LexicalSearchResult mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<?, ?> metadata = parseJson(rs.getString("metadata"));
        double lexicalRank = rs.getDouble("lexical_rank");

        ChunkMetadata chunkMetadata = new ChunkMetadata(intOrNull(metadata.get("page")),
                (String) metadata.get("chapter"), (String) metadata.get("section"),
                intOrNull(metadata.get("paragraph")));

        return new LexicalSearchResult(new DocumentChunkId(UUID.fromString(rs.getString("id"))),
                DocumentId.of((String) metadata.get("documentId")),
                DocumentVersionId.of((String) metadata.get("documentVersionId")), rs.getString("content"),
                chunkMetadata, lexicalRank);
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Map<?, ?> parseJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse chunk metadata read from vector_store", e);
        }
    }
}

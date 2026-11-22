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
 * <p><b>Spanish, OR-semantics and term coverage (FASE 28).</b> This previously used {@code
 * websearch_to_tsquery('english', ?)}, which combined two defects: the {@code 'english'}
 * configuration does not stem Spanish and does not recognize Spanish stopwords (so {@code la},
 * {@code de}, {@code por} became mandatory searchable lexemes), and {@code
 * websearch_to_tsquery} joins unquoted words with AND. A twelve-word Spanish question therefore
 * had to have <em>every</em> lexeme present in a single chunk to match at all - measured
 * outcome: the lexical branch produced candidates in ~3.6% of retrievals, making "hybrid"
 * search semantic-only in practice.
 *
 * <p>The query is now tokenized with the same {@code spanish_unaccent} configuration the index
 * uses ({@code V8__spanish_full_text_search.sql}) and those lexemes are OR-ed, which is what
 * makes the branch actually recall anything. The resulting flood of weak single-term matches is
 * deliberate and safe <em>here</em> - this method's job is candidate generation for RRF fusion
 * and reranking, not deciding whether an answer is grounded. That decision is made by {@code
 * AskInsuranceKnowledgeUseCase} from {@link LexicalSearchResult#termCoverage()}, which is why
 * loosening matching does not loosen grounding. See {@code docs/rag/AUDITORIA_RAG_ES.md}.
 *
 * <p>The lexeme list is reassembled into a {@code tsquery} via {@code quote_literal} and a
 * {@code ::tsquery} cast rather than {@code to_tsquery}: the cast's input parser stores lexemes
 * verbatim, whereas {@code to_tsquery} would run them through a dictionary a second time and
 * could stem or stopword-drop an already-stemmed lexeme, silently changing which terms are
 * searched. The query text itself is still bound as a JDBC parameter - only PostgreSQL-generated
 * lexemes are ever assembled into SQL text, by PostgreSQL itself.
 */
@Component
public class PostgresLexicalSearchAdapter implements LexicalSearchPort {

    private static final String TABLE_NAME = "public.vector_store";

    /**
     * Must stay identical to the configuration {@code V8__spanish_full_text_search.sql} indexes
     * {@code content_tsv} with - query lexemes and indexed lexemes are only comparable when both
     * sides stem and fold accents the same way. Not a configuration property on purpose: it is
     * interpolated into SQL text, and a corpus-language change is a migration (the index must be
     * rebuilt), never a restart-time toggle.
     */
    private static final String TEXT_SEARCH_CONFIG = "spanish_unaccent";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostgresLexicalSearchAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<LexicalSearchResult> search(String query, int topK, RetrievalFilter filter) {
        StringBuilder sql = new StringBuilder("""
                WITH query_lexemes AS (
                    SELECT array_agg(DISTINCT lexeme) AS terms
                    FROM unnest(tsvector_to_array(to_tsvector('%1$s', ?))) AS lexeme
                ), query_tsquery AS (
                    SELECT terms,
                           (SELECT string_agg(quote_literal(term), ' | ')
                            FROM unnest(terms) AS term)::tsquery AS query
                    FROM query_lexemes
                )
                SELECT id,
                       content,
                       metadata,
                       ts_rank_cd(content_tsv, query_tsquery.query) AS lexical_rank,
                       cardinality(query_tsquery.terms) AS query_term_count,
                       (SELECT count(*)
                        FROM unnest(query_tsquery.terms) AS term
                        WHERE term = ANY (tsvector_to_array(content_tsv))) AS matched_term_count
                FROM %2$s, query_tsquery
                WHERE content_tsv @@ query_tsquery.query
                """.formatted(TEXT_SEARCH_CONFIG, TABLE_NAME));
        List<Object> params = new ArrayList<>();
        params.add(query);
        RetrievalFilterSql.appendConditions(sql, params, filter);
        sql.append(" ORDER BY lexical_rank DESC, id LIMIT ?");
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
                chunkMetadata, lexicalRank, rs.getInt("query_term_count"), rs.getInt("matched_term_count"));
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

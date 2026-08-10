package com.rag.springai.insuranceai.adapters.outbound.vectorstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import com.rag.springai.insuranceai.adapters.shared.persistence.RetrievalFilterSql;
import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentChunkId;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.RetrievalFilter;
import com.rag.springai.insuranceai.domain.rag.RetrievedChunk;
import com.rag.springai.insuranceai.ports.outbound.VectorIndexPort;
import com.rag.springai.insuranceai.ports.outbound.VectorSearchPort;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements both {@link VectorSearchPort} and {@link VectorIndexPort} against the
 * {@code vector_store} table (schema owned by {@code V3__vector_store.sql}, matching exactly
 * what Spring AI's {@link PgVectorStore} itself would create).
 *
 * <p><b>Why plain JDBC instead of calling {@code PgVectorStore.add()}/{@code similaritySearch()}
 * directly:</b> those convenience methods always recompute the embedding themselves from raw
 * text, using their own internally-configured {@code EmbeddingModel} - there is no supported
 * way to hand them a pre-computed vector. That is incompatible with brief section 6's required
 * orchestration (the use case must perform "question -&gt; embedding -&gt; vector search" as
 * explicit, separate steps through {@link com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort})
 * and would silently recompute (and re-bill) every embedding a second time. This adapter
 * therefore reuses {@code PgVectorStore}'s own table name, distance-type SQL template and the
 * same {@code com.pgvector:pgvector} JDBC type it depends on, while accepting the
 * already-computed {@link EmbeddingVector} the use case produced. See
 * {@code docs/rag/RAG_DESIGN.md} for the full rationale.
 */
@Component
public class PgVectorStoreAdapter implements VectorSearchPort, VectorIndexPort {

    private static final String TABLE_NAME = PgVectorStore.DEFAULT_SCHEMA_NAME + "." + PgVectorStore.DEFAULT_TABLE_NAME;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PgVectorStoreAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void index(DocumentChunk chunk, EmbeddingVector embedding, EmbeddingModelDescriptor descriptor,
            DocumentType documentType, DocumentClassification documentClassification) {
        String metadataJson = toJson(chunk, descriptor, documentType, documentClassification);
        PGvector vector = toPgVector(embedding);

        jdbcTemplate.update("""
                INSERT INTO %s (id, content, metadata, embedding)
                VALUES (?, ?, ?::json, ?)
                ON CONFLICT (id) DO UPDATE SET content = ?, metadata = ?::json, embedding = ?
                """.formatted(TABLE_NAME),
                chunk.id().value(), chunk.content().value(), metadataJson, vector, chunk.content().value(),
                metadataJson, vector);
    }

    @Override
    public List<RetrievedChunk> search(EmbeddingVector queryEmbedding, int topK, double similarityThreshold,
            RetrievalFilter filter) {
        PGvector vector = toPgVector(queryEmbedding);
        double maxDistance = 1.0 - similarityThreshold;
        StringBuilder filterClause = new StringBuilder();
        List<Object> filterParams = new ArrayList<>();
        RetrievalFilterSql.appendConditions(filterClause, filterParams, filter);
        String sql = PgVectorStore.PgDistanceType.COSINE_DISTANCE.similaritySearchSqlTemplate.formatted(TABLE_NAME,
                filterClause.toString());

        List<Object> params = new ArrayList<>(List.of(vector, vector, maxDistance));
        params.addAll(filterParams);
        params.add(topK);
        return jdbcTemplate.query(sql, this::mapRow, params.toArray());
    }

    private PGvector toPgVector(EmbeddingVector vector) {
        float[] values = new float[vector.values().size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = vector.values().get(i);
        }
        return new PGvector(values);
    }

    private String toJson(DocumentChunk chunk, EmbeddingModelDescriptor descriptor, DocumentType documentType,
            DocumentClassification documentClassification) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", chunk.documentId().toString());
        metadata.put("documentVersionId", chunk.documentVersionId().toString());
        metadata.put("chunkIndex", chunk.index().value());
        metadata.put("page", chunk.metadata().page());
        metadata.put("chapter", chunk.metadata().chapter());
        metadata.put("section", chunk.metadata().section());
        metadata.put("paragraph", chunk.metadata().paragraph());
        metadata.put("embeddingModel", descriptor.model());
        metadata.put("embeddingModelVersion", descriptor.modelVersion());
        metadata.put("embeddingDimension", descriptor.dimensions());
        // FASE 6: denormalized for adapters.shared.persistence.RetrievalFilterSql - see
        // VectorIndexPort's Javadoc for why this is a deliberate, narrow amendment to ADR-005.
        metadata.put("documentType", documentType.name());
        metadata.put("documentClassification", documentClassification.name());
        try {
            return objectMapper.writeValueAsString(metadata);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chunk metadata for indexing", e);
        }
    }

    private RetrievedChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<?, ?> metadata = parseJson(rs.getString("metadata"));
        double distance = rs.getDouble("distance");

        ChunkMetadata chunkMetadata = new ChunkMetadata(intOrNull(metadata.get("page")),
                (String) metadata.get("chapter"), (String) metadata.get("section"),
                intOrNull(metadata.get("paragraph")));

        return new RetrievedChunk(new DocumentChunkId(UUID.fromString(rs.getString("id"))),
                DocumentId.of((String) metadata.get("documentId")),
                DocumentVersionId.of((String) metadata.get("documentVersionId")), rs.getString("content"),
                chunkMetadata, 1.0 - distance);
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

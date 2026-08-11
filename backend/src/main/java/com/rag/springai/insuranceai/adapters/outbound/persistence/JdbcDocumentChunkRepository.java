package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.domain.document.ChunkContent;
import com.rag.springai.insuranceai.domain.document.ChunkIndex;
import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentChunkId;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.ports.outbound.DocumentChunkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation of {@link DocumentChunkRepository}. {@link #replaceAll} deletes then
 * batch-inserts within a single transaction, which is both how idempotent re-processing is
 * achieved (see the port's Javadoc) and reasonable for the "thousands of chunks per version"
 * scale {@code DocumentChunk} was deliberately modeled as its own aggregate for
 * (docs/adr/ADR-003-DOCUMENT-AGGREGATE-BOUNDARIES.md).
 */
@Component
public class JdbcDocumentChunkRepository implements DocumentChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void replaceAll(DocumentVersionId documentVersionId, List<DocumentChunk> chunks) {
        jdbcTemplate.update("DELETE FROM document_chunks WHERE document_version_id = ?",
                documentVersionId.value());

        if (chunks.isEmpty()) {
            return;
        }

        List<Object[]> batchArgs = chunks.stream()
                .map(chunk -> new Object[] { chunk.id().value(), chunk.documentId().value(),
                        chunk.documentVersionId().value(), chunk.index().value(), chunk.content().value(),
                        chunk.metadata().page(), chunk.metadata().chapter(), chunk.metadata().section(),
                        chunk.metadata().paragraph() })
                .toList();

        jdbcTemplate.batchUpdate("""
                INSERT INTO document_chunks
                    (id, document_id, document_version_id, chunk_index, content, page, chapter, section, paragraph)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batchArgs);
    }

    @Override
    public List<DocumentChunk> findByDocumentVersionId(DocumentVersionId documentVersionId) {
        return jdbcTemplate.query(
                "SELECT * FROM document_chunks WHERE document_version_id = ? ORDER BY chunk_index",
                this::mapRow, documentVersionId.value());
    }

    private DocumentChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        ChunkMetadata metadata = new ChunkMetadata((Integer) rs.getObject("page"), rs.getString("chapter"),
                rs.getString("section"), (Integer) rs.getObject("paragraph"));
        return DocumentChunk.reconstitute(new DocumentChunkId(rs.getObject("id", UUID.class)),
                new DocumentId(rs.getObject("document_id", UUID.class)),
                new DocumentVersionId(rs.getObject("document_version_id", UUID.class)),
                new ChunkIndex(rs.getInt("chunk_index")), new ChunkContent(rs.getString("content")), metadata);
    }
}

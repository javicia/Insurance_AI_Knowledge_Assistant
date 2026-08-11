package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.ports.outbound.DocumentContentStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JdbcDocumentContentStore implements DocumentContentStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentContentStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void store(DocumentVersionId documentVersionId, byte[] content) {
        jdbcTemplate.update("""
                INSERT INTO document_version_contents (document_version_id, content)
                VALUES (?, ?)
                ON CONFLICT (document_version_id) DO NOTHING
                """, documentVersionId.value(), content);
    }

    @Override
    public byte[] retrieve(DocumentVersionId documentVersionId) {
        List<byte[]> results = jdbcTemplate.query(
                "SELECT content FROM document_version_contents WHERE document_version_id = ?",
                (rs, rowNum) -> rs.getBytes("content"), documentVersionId.value());
        if (results.isEmpty()) {
            throw new PermanentProcessingException("DOCUMENT_CONTENT_NOT_FOUND",
                    "No stored content found for document version " + documentVersionId);
        }
        return results.get(0);
    }
}

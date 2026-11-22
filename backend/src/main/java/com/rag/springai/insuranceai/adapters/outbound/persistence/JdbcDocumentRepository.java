package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentStatus;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.document.EffectivePeriod;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link DocumentRepository}. Uses plain {@link JdbcTemplate}, not
 * Spring Data JDBC repositories/aggregates: the {@code Document} aggregate's shape (a root
 * plus versions, with hand-written invariants) does not map cleanly onto Spring Data's own
 * aggregate-persistence conventions, and hand-written SQL keeps the mapping fully under our
 * control. The domain package is never imported by anything below this adapter.
 */
@Component
public class JdbcDocumentRepository implements DocumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void save(Document document) {
        jdbcTemplate.update("""
                INSERT INTO documents (id, name, type, product, country, language, classification, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                document.id().value(), document.name(), document.type().name(),
                document.metadata().product(), document.metadata().country(), document.metadata().language(),
                document.metadata().classification().name(), document.metadata().source());

        for (DocumentVersion version : document.versions()) {
            jdbcTemplate.update("""
                    INSERT INTO document_versions
                        (id, document_id, version_major, version_minor, content_hash, effective_from,
                         effective_to, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, updated_at = now()
                    """,
                    version.id().value(), document.id().value(), version.versionNumber().major(),
                    version.versionNumber().minor(), version.contentHash().value(),
                    Timestamp.from(version.effectivePeriod().effectiveFrom()),
                    toNullableTimestamp(version.effectivePeriod().effectiveTo()), version.status().name());
        }
    }

    @Override
    public Optional<Document> findById(DocumentId id) {
        List<Document> documents = jdbcTemplate.query(
                "SELECT * FROM documents WHERE id = ?", this::mapDocumentRow, id.value());
        if (documents.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(hydrateVersions(documents.get(0)));
    }

    @Override
    public Optional<Document> findByVersionContentHash(ContentHash contentHash) {
        List<UUID> documentIds = jdbcTemplate.query(
                "SELECT document_id FROM document_versions WHERE content_hash = ?",
                (rs, rowNum) -> rs.getObject("document_id", UUID.class), contentHash.value());
        if (documentIds.isEmpty()) {
            return Optional.empty();
        }
        return findById(new DocumentId(documentIds.get(0)));
    }

    private Document hydrateVersions(Document document) {
        List<DocumentVersion> versions = jdbcTemplate.query(
                "SELECT * FROM document_versions WHERE document_id = ? ORDER BY version_major, version_minor",
                this::mapVersionRow, document.id().value());
        return Document.reconstitute(document.id(), document.name(), document.type(), document.metadata(), versions);
    }

    private Document mapDocumentRow(ResultSet rs, int rowNum) throws SQLException {
        DocumentMetadata metadata = new DocumentMetadata(rs.getString("product"), rs.getString("country"),
                rs.getString("language"), DocumentClassification.valueOf(rs.getString("classification")),
                rs.getString("source"));
        return Document.reconstitute(new DocumentId(rs.getObject("id", UUID.class)), rs.getString("name"),
                DocumentType.valueOf(rs.getString("type")), metadata, List.of());
    }

    private DocumentVersion mapVersionRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp effectiveTo = rs.getTimestamp("effective_to");
        return DocumentVersion.reconstitute(new DocumentVersionId(rs.getObject("id", UUID.class)),
                new DocumentId(rs.getObject("document_id", UUID.class)),
                VersionNumber.of(rs.getInt("version_major"), rs.getInt("version_minor")),
                ContentHash.ofHex(rs.getString("content_hash")),
                EffectivePeriod.of(rs.getTimestamp("effective_from").toInstant(),
                        effectiveTo == null ? null : effectiveTo.toInstant()),
                DocumentStatus.valueOf(rs.getString("status")));
    }

    private static Timestamp toNullableTimestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}

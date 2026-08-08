package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.TestcontainersConfiguration;
import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentStatus;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.document.EffectivePeriod;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test against a real PostgreSQL instance (Testcontainers, brief section 37/8) -
 * verifies the full save/load round trip, including Flyway-managed schema
 * ({@code V2__documents.sql}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JdbcDocumentRepositoryTest {

    @Autowired
    private JdbcDocumentRepository repository;

    private final Instant day1 = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant day10 = day1.plus(9, ChronoUnit.DAYS);

    @Test
    void savesAndReloadsADocumentWithOneVersion() {
        Document document = Document.register("Home Premium Policy", DocumentType.POLICY,
                new DocumentMetadata("home", "ES", "en", DocumentClassification.INTERNAL, "home-premium.pdf"));
        DocumentVersion version = DocumentVersion.upload(document.id(), VersionNumber.of(1, 0),
                ContentHash.of("home premium content".getBytes()), EffectivePeriod.startingAt(day1));
        document.addVersion(version);

        repository.save(document);
        Document reloaded = repository.findById(document.id()).orElseThrow();

        assertEquals(document.id(), reloaded.id());
        assertEquals(document.name(), reloaded.name());
        assertEquals(document.type(), reloaded.type());
        assertEquals(document.metadata(), reloaded.metadata());
        assertEquals(1, reloaded.versions().size());
        DocumentVersion reloadedVersion = reloaded.versions().get(0);
        assertEquals(version.id(), reloadedVersion.id());
        assertEquals(version.versionNumber(), reloadedVersion.versionNumber());
        assertEquals(version.contentHash(), reloadedVersion.contentHash());
        assertEquals(DocumentStatus.UPLOADED, reloadedVersion.status());
    }

    @Test
    void savingAgainAfterAStatusTransitionPersistsTheNewStatus() {
        Document document = Document.register("Auto Premium Policy", DocumentType.POLICY,
                new DocumentMetadata("auto", "ES", "en", DocumentClassification.INTERNAL, "auto-premium.pdf"));
        DocumentVersion version = DocumentVersion.upload(document.id(), VersionNumber.of(1, 0),
                ContentHash.of("auto premium content".getBytes()), EffectivePeriod.startingAt(day1));
        document.addVersion(version);
        repository.save(document);

        version.startProcessing();
        version.markProcessed();
        repository.save(document);

        Document reloaded = repository.findById(document.id()).orElseThrow();
        assertEquals(DocumentStatus.PROCESSED, reloaded.versions().get(0).status());
    }

    @Test
    void savingASecondVersionAddsItWithoutDuplicatingTheFirst() {
        Document document = Document.register("Life Basic Policy", DocumentType.POLICY,
                new DocumentMetadata("life", "ES", "en", DocumentClassification.INTERNAL, "life-basic.pdf"));
        document.addVersion(DocumentVersion.upload(document.id(), VersionNumber.of(1, 0),
                ContentHash.of("life v1".getBytes()), EffectivePeriod.of(day1, day10)));
        repository.save(document);

        document.addVersion(DocumentVersion.upload(document.id(), VersionNumber.of(2, 0),
                ContentHash.of("life v2".getBytes()), EffectivePeriod.startingAt(day10)));
        repository.save(document);

        Document reloaded = repository.findById(document.id()).orElseThrow();
        assertEquals(2, reloaded.versions().size());
    }

    @Test
    void findsADocumentByItsVersionContentHash() {
        Document document = Document.register("Claims Management Procedure", DocumentType.CLAIMS_PROCEDURE,
                new DocumentMetadata(null, "ES", "en", DocumentClassification.INTERNAL, "claims-procedure.pdf"));
        ContentHash contentHash = ContentHash.of("claims management procedure content".getBytes());
        document.addVersion(
                DocumentVersion.upload(document.id(), VersionNumber.of(1, 0), contentHash,
                        EffectivePeriod.startingAt(day1)));
        repository.save(document);

        Optional<Document> found = repository.findByVersionContentHash(contentHash);

        assertTrue(found.isPresent());
        assertEquals(document.id(), found.get().id());
    }

    @Test
    void returnsEmptyForAnUnknownContentHash() {
        Optional<Document> found = repository.findByVersionContentHash(
                ContentHash.of("never uploaded".getBytes()));

        assertTrue(found.isEmpty());
    }

    @Test
    void returnsEmptyForAnUnknownDocumentId() {
        assertTrue(repository.findById(DocumentId.generate()).isEmpty());
    }
}

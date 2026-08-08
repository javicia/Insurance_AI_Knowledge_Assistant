package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.TestcontainersConfiguration;
import com.rag.springai.insuranceai.domain.document.ChunkContent;
import com.rag.springai.insuranceai.domain.document.ChunkIndex;
import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.document.EffectivePeriod;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JdbcDocumentChunkRepositoryTest {

    @Autowired
    private JdbcDocumentRepository documentRepository;

    @Autowired
    private JdbcDocumentChunkRepository chunkRepository;

    private DocumentVersion registerAVersion() {
        Document document = Document.register("Water Damage Coverage", DocumentType.POLICY,
                new DocumentMetadata("home", "ES", "en", DocumentClassification.INTERNAL, "water-damage.pdf"));
        DocumentVersion version = DocumentVersion.upload(document.id(), VersionNumber.of(1, 0),
                ContentHash.of(("water damage " + System.nanoTime()).getBytes()),
                EffectivePeriod.startingAt(Instant.parse("2026-01-01T00:00:00Z")));
        document.addVersion(version);
        documentRepository.save(document);
        return version;
    }

    @Test
    void persistsAndRetrievesChunksInIndexOrder() {
        DocumentVersion version = registerAVersion();
        DocumentChunk chunk0 = DocumentChunk.create(version.documentId(), version.id(), new ChunkIndex(0),
                new ChunkContent("first chunk"), new ChunkMetadata(37, null, "7.2 Water Damage", 1));
        DocumentChunk chunk1 = DocumentChunk.create(version.documentId(), version.id(), new ChunkIndex(1),
                new ChunkContent("second chunk"), ChunkMetadata.empty());

        chunkRepository.replaceAll(version.id(), List.of(chunk0, chunk1));
        List<DocumentChunk> found = chunkRepository.findByDocumentVersionId(version.id());

        assertEquals(2, found.size());
        assertEquals("first chunk", found.get(0).content().value());
        assertEquals(37, found.get(0).metadata().page());
        assertEquals("7.2 Water Damage", found.get(0).metadata().section());
        assertEquals("second chunk", found.get(1).content().value());
    }

    @Test
    void replaceAllReplacesRatherThanAppendsOnReprocessing() {
        DocumentVersion version = registerAVersion();
        DocumentChunk firstAttemptChunk = DocumentChunk.create(version.documentId(), version.id(),
                new ChunkIndex(0), new ChunkContent("attempt 1 chunk"), ChunkMetadata.empty());
        chunkRepository.replaceAll(version.id(), List.of(firstAttemptChunk));

        DocumentChunk secondAttemptChunk = DocumentChunk.create(version.documentId(), version.id(),
                new ChunkIndex(0), new ChunkContent("attempt 2 chunk"), ChunkMetadata.empty());
        chunkRepository.replaceAll(version.id(), List.of(secondAttemptChunk));

        List<DocumentChunk> found = chunkRepository.findByDocumentVersionId(version.id());
        assertEquals(1, found.size(), "re-running chunking for the same version must not duplicate chunks");
        assertEquals("attempt 2 chunk", found.get(0).content().value());
    }

    @Test
    void returnsAnEmptyListForAVersionWithNoChunks() {
        DocumentVersion version = registerAVersion();

        assertTrue(chunkRepository.findByDocumentVersionId(version.id()).isEmpty());
    }
}

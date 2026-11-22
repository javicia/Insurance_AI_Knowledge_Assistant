package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.TestcontainersConfiguration;
import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.document.EffectivePeriod;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import com.rag.springai.insuranceai.domain.shared.exception.PermanentProcessingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(com.rag.springai.insuranceai.DatabaseCleanupExtension.class)
class JdbcDocumentContentStoreTest {

    @Autowired
    private JdbcDocumentRepository documentRepository;

    @Autowired
    private JdbcDocumentContentStore contentStore;

    @Test
    void storesAndRetrievesRawBytes() {
        DocumentVersionId versionId = registerAVersion();
        byte[] content = "%PDF-1.4 fake pdf bytes".getBytes();

        contentStore.store(versionId, content);

        assertArrayEquals(content, contentStore.retrieve(versionId));
    }

    @Test
    void throwsAPermanentProcessingExceptionWhenNoContentWasStored() {
        DocumentVersionId neverStored = DocumentVersionId.generate();

        assertThrows(PermanentProcessingException.class, () -> contentStore.retrieve(neverStored));
    }

    private DocumentVersionId registerAVersion() {
        Document document = Document.register("Customer Service Protocol", DocumentType.CORPORATE,
                new DocumentMetadata(null, null, "en", DocumentClassification.INTERNAL, "customer-service.pdf"));
        DocumentVersion version = DocumentVersion.upload(document.id(), VersionNumber.of(1, 0),
                ContentHash.of(("customer service " + System.nanoTime()).getBytes()),
                EffectivePeriod.startingAt(Instant.parse("2026-01-01T00:00:00Z")));
        document.addVersion(version);
        documentRepository.save(document);
        return version.id();
    }
}

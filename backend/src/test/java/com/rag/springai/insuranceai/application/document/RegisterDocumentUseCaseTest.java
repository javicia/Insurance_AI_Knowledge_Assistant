package com.rag.springai.insuranceai.application.document;

import com.rag.springai.insuranceai.domain.document.ContentHash;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentStatus;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterDocumentUseCaseTest {

    private final InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
    private final InMemoryDocumentContentStore contentStore = new InMemoryDocumentContentStore();
    private final RecordingDocumentEventPublisher eventPublisher = new RecordingDocumentEventPublisher();
    private final RegisterDocumentUseCase useCase = new RegisterDocumentUseCase(repository, contentStore,
            eventPublisher);

    @Test
    void registersADocumentStartingAtVersionOneDotZero() {
        byte[] content = "Home Premium Policy content".getBytes();
        RegisterDocumentCommand command = new RegisterDocumentCommand("Home Premium Policy", DocumentType.POLICY,
                new DocumentMetadata("home", "ES", "en", DocumentClassification.INTERNAL, "home-premium.pdf"),
                content, Instant.parse("2026-01-01T00:00:00Z"));

        Document document = useCase.register(command);

        assertEquals(1, document.versions().size());
        DocumentVersion version = document.versions().get(0);
        assertEquals(VersionNumber.of(1, 0), version.versionNumber());
        assertEquals(DocumentStatus.UPLOADED, version.status());
        assertEquals(ContentHash.of(content), version.contentHash());
    }

    @Test
    void persistsTheRegisteredDocument() {
        RegisterDocumentCommand command = new RegisterDocumentCommand("Auto Premium Policy", DocumentType.POLICY,
                new DocumentMetadata("auto", "ES", "en", DocumentClassification.INTERNAL, "auto-premium.pdf"),
                "content".getBytes(), Instant.parse("2026-01-01T00:00:00Z"));

        Document document = useCase.register(command);

        Optional<Document> stored = repository.findById(document.id());
        assertTrue(stored.isPresent());
        assertEquals(document.id(), stored.get().id());
    }

    @Test
    void storesTheRawContentAndPublishesAnUploadedEvent() {
        byte[] content = "Life Basic Policy content".getBytes();
        RegisterDocumentCommand command = new RegisterDocumentCommand("Life Basic Policy", DocumentType.POLICY,
                new DocumentMetadata("life", "ES", "en", DocumentClassification.INTERNAL, "life-basic.pdf"), content,
                Instant.parse("2026-01-01T00:00:00Z"));

        Document document = useCase.register(command);
        DocumentVersion version = document.versions().get(0);

        assertArrayEquals(content, contentStore.retrieve(version.id()));
        assertEquals(1, eventPublisher.uploadedEvents.size());
        assertEquals(version.id(), eventPublisher.uploadedEvents.get(0).documentVersionId());
    }

    @Test
    void reRegisteringIdenticalContentIsIdempotentAndReturnsTheExistingDocument() {
        byte[] content = "Water Damage Coverage Procedure".getBytes();
        RegisterDocumentCommand command = new RegisterDocumentCommand("Water Damage Procedure",
                DocumentType.CLAIMS_PROCEDURE,
                new DocumentMetadata(null, "ES", "en", DocumentClassification.INTERNAL, "water-damage.pdf"), content,
                Instant.parse("2026-01-01T00:00:00Z"));

        Document first = useCase.register(command);
        Document second = useCase.register(command);

        assertEquals(first.id(), second.id());
        assertEquals(1, first.versions().size());
        assertEquals(1, eventPublisher.uploadedEvents.size(),
                "a duplicate registration of identical content must not publish a second event");
    }
}

package com.rag.springai.insuranceai.application.document;

import com.rag.springai.insuranceai.application.document.exception.DocumentNotFoundException;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.document.EffectivePeriod;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import com.rag.springai.insuranceai.domain.document.exception.OverlappingEffectivePeriodException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AddDocumentVersionUseCaseTest {

    private final InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
    private final InMemoryDocumentContentStore contentStore = new InMemoryDocumentContentStore();
    private final RecordingDocumentEventPublisher eventPublisher = new RecordingDocumentEventPublisher();
    private final AddDocumentVersionUseCase useCase = new AddDocumentVersionUseCase(repository, contentStore,
            eventPublisher);

    private final Instant day1 = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant day10 = day1.plus(9, ChronoUnit.DAYS);

    private Document registerDocumentWithFirstVersion() {
        Document document = Document.register("Home Premium Policy", DocumentType.POLICY,
                new DocumentMetadata("home", "ES", "en", DocumentClassification.INTERNAL, "home-premium.pdf"));
        document.addVersion(DocumentVersion.upload(document.id(), VersionNumber.of(1, 0),
                com.rag.springai.insuranceai.domain.document.ContentHash.of("v1".getBytes()),
                EffectivePeriod.of(day1, day10)));
        repository.save(document);
        return document;
    }

    @Test
    void addsANewNonConflictingVersion() {
        Document document = registerDocumentWithFirstVersion();
        AddDocumentVersionCommand command = new AddDocumentVersionCommand(document.id(), VersionNumber.of(2, 0),
                "v2".getBytes(), EffectivePeriod.startingAt(day10));

        DocumentVersion newVersion = useCase.addVersion(command);

        Document reloaded = repository.findById(document.id()).orElseThrow();
        assertEquals(2, reloaded.versions().size());
        assertEquals(VersionNumber.of(2, 0), newVersion.versionNumber());
    }

    @Test
    void rejectsAddingAVersionToAnUnknownDocument() {
        AddDocumentVersionCommand command = new AddDocumentVersionCommand(DocumentId.generate(),
                VersionNumber.of(1, 0), "content".getBytes(), EffectivePeriod.startingAt(day1));

        assertThrows(DocumentNotFoundException.class, () -> useCase.addVersion(command));
    }

    @Test
    void propagatesTheDomainOverlapInvariant() {
        Document document = registerDocumentWithFirstVersion();
        AddDocumentVersionCommand overlapping = new AddDocumentVersionCommand(document.id(), VersionNumber.of(2, 0),
                "v2".getBytes(), EffectivePeriod.startingAt(day1.plus(5, ChronoUnit.DAYS)));

        assertThrows(OverlappingEffectivePeriodException.class, () -> useCase.addVersion(overlapping));
    }

    @Test
    void addingIdenticalContentTwiceIsIdempotent() {
        Document document = registerDocumentWithFirstVersion();
        AddDocumentVersionCommand command = new AddDocumentVersionCommand(document.id(), VersionNumber.of(2, 0),
                "v2".getBytes(), EffectivePeriod.startingAt(day10));

        DocumentVersion first = useCase.addVersion(command);
        DocumentVersion second = useCase.addVersion(command);

        assertEquals(first.id(), second.id());
        assertEquals(2, repository.findById(document.id()).orElseThrow().versions().size());
        assertEquals(1, eventPublisher.uploadedEvents.size(),
                "a duplicate addVersion call with identical content must not publish a second event");
    }
}

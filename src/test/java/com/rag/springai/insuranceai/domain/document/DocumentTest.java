package com.rag.springai.insuranceai.domain.document;

import com.rag.springai.insuranceai.domain.document.exception.DuplicateVersionNumberException;
import com.rag.springai.insuranceai.domain.document.exception.OverlappingEffectivePeriodException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTest {

    private final Instant day1 = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant day10 = day1.plus(9, ChronoUnit.DAYS);
    private final Instant day20 = day1.plus(19, ChronoUnit.DAYS);

    private final DocumentMetadata metadata = new DocumentMetadata("home", "ES", "en",
            DocumentClassification.INTERNAL, "home-premium.pdf");

    private Document newDocument() {
        return Document.register("Home Premium Policy", DocumentType.POLICY, metadata);
    }

    private DocumentVersion versionFor(Document document, int major, int minor, Instant from, Instant to) {
        return DocumentVersion.upload(document.id(), VersionNumber.of(major, minor),
                ContentHash.of(("content-" + major + "." + minor).getBytes()), EffectivePeriod.of(from, to));
    }

    @Test
    void registeringCreatesADocumentWithNoVersions() {
        Document document = newDocument();

        assertTrue(document.versions().isEmpty());
    }

    @Test
    void rejectsABlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> Document.register("   ", DocumentType.POLICY, metadata));
    }

    @Test
    void addsANonConflictingVersionSuccessfully() {
        Document document = newDocument();

        DocumentVersion version = versionFor(document, 1, 0, day1, null);
        document.addVersion(version);

        assertEquals(List.of(version), document.versions());
    }

    @Test
    void rejectsAVersionThatDoesNotBelongToThisDocument() {
        Document document = newDocument();
        DocumentVersion foreignVersion = DocumentVersion.upload(DocumentId.generate(), VersionNumber.of(1, 0),
                ContentHash.of("x".getBytes()), EffectivePeriod.startingAt(day1));

        assertThrows(IllegalArgumentException.class, () -> document.addVersion(foreignVersion));
    }

    @Test
    void rejectsADuplicateVersionNumber() {
        Document document = newDocument();
        document.addVersion(versionFor(document, 1, 0, day1, day10));

        DocumentVersion duplicate = versionFor(document, 1, 0, day10, day20);

        assertThrows(DuplicateVersionNumberException.class, () -> document.addVersion(duplicate));
    }

    @Test
    void rejectsAnOverlappingEffectivePeriodEvenWithADifferentVersionNumber() {
        Document document = newDocument();
        document.addVersion(versionFor(document, 1, 0, day1, day20));

        DocumentVersion overlapping = versionFor(document, 2, 0, day10, null);

        assertThrows(OverlappingEffectivePeriodException.class, () -> document.addVersion(overlapping));
    }

    @Test
    void acceptsASubsequentVersionThatStartsExactlyWhenThePreviousOneEnds() {
        Document document = newDocument();
        document.addVersion(versionFor(document, 1, 0, day1, day10));

        DocumentVersion sequential = versionFor(document, 2, 0, day10, day20);

        document.addVersion(sequential);

        assertEquals(2, document.versions().size());
    }

    @Test
    void resolvesTheCurrentlyEffectiveVersionAmongSeveral() {
        Document document = newDocument();
        DocumentVersion expired = versionFor(document, 1, 0, day1, day10);
        DocumentVersion current = versionFor(document, 2, 0, day10, null);
        document.addVersion(expired);
        document.addVersion(current);

        Optional<DocumentVersion> resolved = document.currentEffectiveVersion(day20);

        assertEquals(Optional.of(current), resolved);
    }

    @Test
    void resolvesAnExpiredVersionAtItsOwnTime() {
        Document document = newDocument();
        DocumentVersion expired = versionFor(document, 1, 0, day1, day10);
        document.addVersion(expired);
        document.addVersion(versionFor(document, 2, 0, day10, null));

        Optional<DocumentVersion> resolved = document.currentEffectiveVersion(day1);

        assertEquals(Optional.of(expired), resolved);
    }

    @Test
    void returnsEmptyWhenNoVersionIsEffectiveAtTheGivenInstant() {
        Document document = newDocument();
        document.addVersion(versionFor(document, 1, 0, day10, day20));

        Optional<DocumentVersion> resolved = document.currentEffectiveVersion(day1);

        assertEquals(Optional.empty(), resolved);
    }

    @Test
    void findsAVersionById() {
        Document document = newDocument();
        DocumentVersion version = versionFor(document, 1, 0, day1, null);
        document.addVersion(version);

        assertEquals(Optional.of(version), document.version(version.id()));
    }

    @Test
    void reconstituteReplaysAllInvariantsOverExistingVersions() {
        DocumentId id = DocumentId.generate();
        DocumentVersion v1 = DocumentVersion.reconstitute(DocumentVersionId.generate(), id, VersionNumber.of(1, 0),
                ContentHash.of("a".getBytes()), EffectivePeriod.of(day1, day10), DocumentStatus.EMBEDDED);
        DocumentVersion overlappingV2 = DocumentVersion.reconstitute(DocumentVersionId.generate(), id,
                VersionNumber.of(2, 0), ContentHash.of("b".getBytes()), EffectivePeriod.of(day1, day20),
                DocumentStatus.EMBEDDED);

        assertThrows(OverlappingEffectivePeriodException.class, () -> Document.reconstitute(id, "Doc",
                DocumentType.POLICY, metadata, List.of(v1, overlappingV2)));
    }
}

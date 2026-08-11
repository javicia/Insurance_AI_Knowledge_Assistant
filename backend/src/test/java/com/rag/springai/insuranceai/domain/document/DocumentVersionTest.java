package com.rag.springai.insuranceai.domain.document;

import com.rag.springai.insuranceai.domain.document.exception.InvalidDocumentVersionTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentVersionTest {

    private final DocumentId documentId = DocumentId.generate();
    private final ContentHash contentHash = ContentHash.of("content".getBytes());
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    private DocumentVersion newUploadedVersion() {
        return DocumentVersion.upload(documentId, VersionNumber.of(1, 0), contentHash,
                EffectivePeriod.startingAt(now));
    }

    @Test
    void startsInUploadedStatus() {
        DocumentVersion version = newUploadedVersion();

        assertEquals(DocumentStatus.UPLOADED, version.status());
    }

    @Test
    void followsTheHappyPathThroughAllStates() {
        DocumentVersion version = newUploadedVersion();

        version.startProcessing();
        assertEquals(DocumentStatus.PROCESSING, version.status());

        version.markProcessed();
        assertEquals(DocumentStatus.PROCESSED, version.status());

        version.markEmbedded();
        assertEquals(DocumentStatus.EMBEDDED, version.status());
    }

    @Test
    void canFailDuringProcessing() {
        DocumentVersion version = newUploadedVersion();
        version.startProcessing();

        version.markFailed();

        assertEquals(DocumentStatus.FAILED, version.status());
    }

    @Test
    void canFailAfterProcessedWhileEmbedding() {
        DocumentVersion version = newUploadedVersion();
        version.startProcessing();
        version.markProcessed();

        version.markFailed();

        assertEquals(DocumentStatus.FAILED, version.status());
    }

    @Test
    void rejectsSkippingProcessing() {
        DocumentVersion version = newUploadedVersion();

        assertThrows(InvalidDocumentVersionTransitionException.class, version::markProcessed);
    }

    @Test
    void rejectsSkippingProcessed() {
        DocumentVersion version = newUploadedVersion();
        version.startProcessing();

        assertThrows(InvalidDocumentVersionTransitionException.class, version::markEmbedded);
    }

    @Test
    void rejectsAnyTransitionOutOfEmbedded() {
        DocumentVersion version = newUploadedVersion();
        version.startProcessing();
        version.markProcessed();
        version.markEmbedded();

        assertThrows(InvalidDocumentVersionTransitionException.class, version::startProcessing);
        assertThrows(InvalidDocumentVersionTransitionException.class, version::markFailed);
    }

    @Test
    void rejectsAnyTransitionOutOfFailed() {
        DocumentVersion version = newUploadedVersion();
        version.startProcessing();
        version.markFailed();

        assertThrows(InvalidDocumentVersionTransitionException.class, version::startProcessing);
    }

    @Test
    void delegatesEffectivenessToItsEffectivePeriod() {
        DocumentVersion version = DocumentVersion.upload(documentId, VersionNumber.of(1, 0), contentHash,
                EffectivePeriod.of(now, now.plus(10, ChronoUnit.DAYS)));

        assertTrue(version.isEffectiveAt(now));
        assertFalse(version.isEffectiveAt(now.plus(20, ChronoUnit.DAYS)));
    }

    @Test
    void twoVersionsWithDifferentIdsAreNotEqual() {
        DocumentVersion first = newUploadedVersion();
        DocumentVersion second = newUploadedVersion();

        assertFalse(first.equals(second));
    }

    @Test
    void reconstitutionPreservesGivenIdentityAndStatus() {
        DocumentVersionId id = DocumentVersionId.generate();

        DocumentVersion version = DocumentVersion.reconstitute(id, documentId, VersionNumber.of(2, 0), contentHash,
                EffectivePeriod.startingAt(now), DocumentStatus.PROCESSED);

        assertEquals(id, version.id());
        assertEquals(DocumentStatus.PROCESSED, version.status());
    }
}

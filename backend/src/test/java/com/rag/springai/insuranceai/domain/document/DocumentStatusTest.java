package com.rag.springai.insuranceai.domain.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive transition-matrix test for the lifecycle documented on
 * {@link DocumentStatus}: {@code UPLOADED -> PROCESSING -> PROCESSED -> EMBEDDED}, with
 * {@code PROCESSING} and {@code PROCESSED} both able to fail into {@code FAILED}.
 */
class DocumentStatusTest {

    @Test
    void uploadedMayOnlyMoveToProcessing() {
        assertTrue(DocumentStatus.UPLOADED.canTransitionTo(DocumentStatus.PROCESSING));
        assertFalse(DocumentStatus.UPLOADED.canTransitionTo(DocumentStatus.PROCESSED));
        assertFalse(DocumentStatus.UPLOADED.canTransitionTo(DocumentStatus.EMBEDDED));
        assertFalse(DocumentStatus.UPLOADED.canTransitionTo(DocumentStatus.FAILED));
        assertFalse(DocumentStatus.UPLOADED.canTransitionTo(DocumentStatus.UPLOADED));
    }

    @Test
    void processingMayMoveToProcessedOrFailed() {
        assertTrue(DocumentStatus.PROCESSING.canTransitionTo(DocumentStatus.PROCESSED));
        assertTrue(DocumentStatus.PROCESSING.canTransitionTo(DocumentStatus.FAILED));
        assertFalse(DocumentStatus.PROCESSING.canTransitionTo(DocumentStatus.EMBEDDED));
        assertFalse(DocumentStatus.PROCESSING.canTransitionTo(DocumentStatus.UPLOADED));
        assertFalse(DocumentStatus.PROCESSING.canTransitionTo(DocumentStatus.PROCESSING));
    }

    @Test
    void processedMayMoveToEmbeddedOrFailed() {
        assertTrue(DocumentStatus.PROCESSED.canTransitionTo(DocumentStatus.EMBEDDED));
        assertTrue(DocumentStatus.PROCESSED.canTransitionTo(DocumentStatus.FAILED));
        assertFalse(DocumentStatus.PROCESSED.canTransitionTo(DocumentStatus.UPLOADED));
        assertFalse(DocumentStatus.PROCESSED.canTransitionTo(DocumentStatus.PROCESSING));
        assertFalse(DocumentStatus.PROCESSED.canTransitionTo(DocumentStatus.PROCESSED));
    }

    @ParameterizedTest
    @EnumSource(DocumentStatus.class)
    void embeddedIsTerminal(DocumentStatus target) {
        assertFalse(DocumentStatus.EMBEDDED.canTransitionTo(target));
    }

    @ParameterizedTest
    @EnumSource(DocumentStatus.class)
    void failedIsTerminal(DocumentStatus target) {
        assertFalse(DocumentStatus.FAILED.canTransitionTo(target));
    }
}

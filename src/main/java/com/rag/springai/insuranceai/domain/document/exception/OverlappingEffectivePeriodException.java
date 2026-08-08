package com.rag.springai.insuranceai.domain.document.exception;

import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.VersionNumber;
import com.rag.springai.insuranceai.domain.shared.exception.DomainException;

/**
 * Raised when adding a {@code DocumentVersion} whose effective period overlaps with an
 * already-registered version of the same {@code Document} (brief section 11: overlapping
 * versions must be rejected).
 */
public final class OverlappingEffectivePeriodException extends DomainException {

    public OverlappingEffectivePeriodException(DocumentId documentId, VersionNumber existingVersion,
            VersionNumber incomingVersion) {
        super("DOCUMENT_VERSION_OVERLAPPING_EFFECTIVE_PERIOD",
                "Version " + incomingVersion + " overlaps with existing version " + existingVersion
                        + " of document " + documentId);
    }
}

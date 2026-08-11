package com.rag.springai.insuranceai.domain.document;

import com.rag.springai.insuranceai.domain.document.exception.InvalidDocumentVersionTransitionException;

import java.time.Instant;
import java.util.Objects;

/**
 * A specific, immutable-content revision of a {@link Document} (e.g. "Home Premium Policy
 * v3.2"). Modeled as an <b>Entity</b> owned by the {@code Document} aggregate root, not as a
 * standalone aggregate: the invariants that matter for a version (no two versions of the same
 * document may have overlapping {@link EffectivePeriod}s, version numbers must be unique) are
 * invariants of the <em>document as a whole</em>, so they must be enforced by the aggregate
 * that can see every version at once - see {@code Document#addVersion} and
 * {@code docs/adr/ADR-003-DOCUMENT-AGGREGATE-BOUNDARIES.md}.
 *
 * <p>Its content ({@link DocumentChunk}s) is deliberately not held here - see
 * {@link DocumentChunk}'s Javadoc.
 */
public final class DocumentVersion {

    private final DocumentVersionId id;
    private final DocumentId documentId;
    private final VersionNumber versionNumber;
    private final ContentHash contentHash;
    private final EffectivePeriod effectivePeriod;
    private DocumentStatus status;

    private DocumentVersion(DocumentVersionId id, DocumentId documentId, VersionNumber versionNumber,
            ContentHash contentHash, EffectivePeriod effectivePeriod, DocumentStatus status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.versionNumber = Objects.requireNonNull(versionNumber, "versionNumber must not be null");
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null");
        this.effectivePeriod = Objects.requireNonNull(effectivePeriod, "effectivePeriod must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public static DocumentVersion upload(DocumentId documentId, VersionNumber versionNumber,
            ContentHash contentHash, EffectivePeriod effectivePeriod) {
        return new DocumentVersion(DocumentVersionId.generate(), documentId, versionNumber, contentHash,
                effectivePeriod, DocumentStatus.UPLOADED);
    }

    public static DocumentVersion reconstitute(DocumentVersionId id, DocumentId documentId,
            VersionNumber versionNumber, ContentHash contentHash, EffectivePeriod effectivePeriod,
            DocumentStatus status) {
        return new DocumentVersion(id, documentId, versionNumber, contentHash, effectivePeriod, status);
    }

    public void startProcessing() {
        transitionTo(DocumentStatus.PROCESSING);
    }

    public void markProcessed() {
        transitionTo(DocumentStatus.PROCESSED);
    }

    public void markEmbedded() {
        transitionTo(DocumentStatus.EMBEDDED);
    }

    public void markFailed() {
        transitionTo(DocumentStatus.FAILED);
    }

    private void transitionTo(DocumentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidDocumentVersionTransitionException(id, status, target);
        }
        this.status = target;
    }

    public boolean isEffectiveAt(Instant instant) {
        return effectivePeriod.isEffectiveAt(instant);
    }

    public DocumentVersionId id() {
        return id;
    }

    public DocumentId documentId() {
        return documentId;
    }

    public VersionNumber versionNumber() {
        return versionNumber;
    }

    public ContentHash contentHash() {
        return contentHash;
    }

    public EffectivePeriod effectivePeriod() {
        return effectivePeriod;
    }

    public DocumentStatus status() {
        return status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DocumentVersion other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

package com.rag.springai.insuranceai.domain.document;

import com.rag.springai.insuranceai.domain.document.exception.DuplicateVersionNumberException;
import com.rag.springai.insuranceai.domain.document.exception.OverlappingEffectivePeriodException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root of the Document Management bounded context. Owns its {@link DocumentVersion}
 * entities and is the sole authority for invariants that span multiple versions: no two
 * versions may have overlapping {@link EffectivePeriod}s, and no two versions may share a
 * {@link VersionNumber}. See {@code docs/adr/ADR-003-DOCUMENT-AGGREGATE-BOUNDARIES.md} for why
 * versions are included in this aggregate while their {@link DocumentChunk}s are not.
 */
public final class Document {

    private final DocumentId id;
    private final String name;
    private final DocumentType type;
    private final DocumentMetadata metadata;
    private final List<DocumentVersion> versions = new ArrayList<>();

    private Document(DocumentId id, String name, DocumentType type, DocumentMetadata metadata) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = requireNonBlank(name);
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    }

    public static Document register(String name, DocumentType type, DocumentMetadata metadata) {
        return new Document(DocumentId.generate(), name, type, metadata);
    }

    public static Document reconstitute(DocumentId id, String name, DocumentType type, DocumentMetadata metadata,
            List<DocumentVersion> versions) {
        Document document = new Document(id, name, type, metadata);
        versions.forEach(document::addVersion);
        return document;
    }

    public DocumentVersion addVersion(DocumentVersion newVersion) {
        Objects.requireNonNull(newVersion, "newVersion must not be null");
        if (!newVersion.documentId().equals(this.id)) {
            throw new IllegalArgumentException(
                    "version " + newVersion.id() + " does not belong to document " + id);
        }
        for (DocumentVersion existing : versions) {
            if (existing.id().equals(newVersion.id())) {
                throw new IllegalArgumentException("version " + newVersion.id() + " already added");
            }
            if (existing.versionNumber().equals(newVersion.versionNumber())) {
                throw new DuplicateVersionNumberException(id, newVersion.versionNumber());
            }
            if (existing.effectivePeriod().overlaps(newVersion.effectivePeriod())) {
                throw new OverlappingEffectivePeriodException(id, existing.versionNumber(),
                        newVersion.versionNumber());
            }
        }
        versions.add(newVersion);
        return newVersion;
    }

    /**
     * Resolves which version is effective at a given instant without any persistence
     * involved (brief section 9) - relies purely on {@link EffectivePeriod#isEffectiveAt}.
     * Guaranteed to match at most one version, since {@link #addVersion} rejects overlaps.
     */
    public Optional<DocumentVersion> currentEffectiveVersion(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf must not be null");
        return versions.stream().filter(version -> version.isEffectiveAt(asOf)).findFirst();
    }

    public Optional<DocumentVersion> version(DocumentVersionId versionId) {
        Objects.requireNonNull(versionId, "versionId must not be null");
        return versions.stream().filter(version -> version.id().equals(versionId)).findFirst();
    }

    public List<DocumentVersion> versions() {
        return Collections.unmodifiableList(versions);
    }

    public DocumentId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public DocumentType type() {
        return type;
    }

    public DocumentMetadata metadata() {
        return metadata;
    }

    private static String requireNonBlank(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Document other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

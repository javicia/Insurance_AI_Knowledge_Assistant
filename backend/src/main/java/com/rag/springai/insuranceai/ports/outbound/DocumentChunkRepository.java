package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.document.DocumentChunk;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;

import java.util.List;

/**
 * Outbound port for {@link DocumentChunk} persistence - a separate port from
 * {@link DocumentRepository} because {@code DocumentChunk} is its own aggregate (see
 * {@code docs/adr/ADR-003-DOCUMENT-AGGREGATE-BOUNDARIES.md}).
 *
 * <p>{@link #replaceAll} has replace, not append, semantics: it atomically removes any chunks
 * previously stored for the given version and stores the given set instead. This is what makes
 * re-running the chunking step for the same version idempotent regardless of how many times it
 * executes (brief section 34, {@code docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md}).
 */
public interface DocumentChunkRepository {

    void replaceAll(DocumentVersionId documentVersionId, List<DocumentChunk> chunks);

    List<DocumentChunk> findByDocumentVersionId(DocumentVersionId documentVersionId);
}

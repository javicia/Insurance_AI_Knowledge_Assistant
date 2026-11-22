package com.rag.springai.insuranceai.domain.document;

/**
 * Lifecycle state of a {@link DocumentVersion}'s ingestion pipeline (brief section 3/9):
 *
 * <pre>
 * UPLOADED -&gt; PROCESSING -&gt; PROCESSED -&gt; EMBEDDED
 *                 |              |
 *                 v              v
 *               FAILED        FAILED
 * </pre>
 *
 * {@code EMBEDDED} and {@code FAILED} are terminal: a failed attempt is not resumed in place,
 * it is superseded by re-ingesting a new version (out of scope for this domain model).
 */
public enum DocumentStatus {
    UPLOADED,
    PROCESSING,
    PROCESSED,
    EMBEDDED,
    FAILED;

    public boolean canTransitionTo(DocumentStatus target) {
        return switch (this) {
            case UPLOADED -> target == PROCESSING;
            case PROCESSING -> target == PROCESSED || target == FAILED;
            case PROCESSED -> target == EMBEDDED || target == FAILED;
            case EMBEDDED, FAILED -> false;
        };
    }
}

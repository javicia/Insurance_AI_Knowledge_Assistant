package com.rag.springai.insuranceai.adapters.shared.messaging;

/**
 * Names of the document-ingestion Kafka topics (brief section 33). Only {@code UPLOADED} and
 * {@code PROCESSED} are actually produced/consumed in FASE 4 - {@code EMBEDDED} is named here
 * for documentation of the full intended pipeline but has no producer or consumer until
 * FASE 5, matching brief section 5's instruction not to fake pipeline stages that do not exist
 * yet.
 */
public final class DocumentTopics {

    public static final String DOCUMENT_UPLOADED = "insurance.document.uploaded";
    public static final String DOCUMENT_PROCESSED = "insurance.document.processed";
    public static final String DOCUMENT_EMBEDDED = "insurance.document.embedded";

    private DocumentTopics() {
    }
}

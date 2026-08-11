package com.rag.springai.insuranceai.adapters.inbound.rest;

import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentType;

/**
 * Optional retrieval metadata filters on {@code POST /api/chat} (brief FASE 6 section 6/26).
 * Every field is optional; enum fields deserialize the same way {@code DocumentController}'s
 * upload request params already do, so an invalid value produces the same clean 400 via {@code
 * GlobalExceptionHandler}. {@code documentId}/{@code documentVersionId} stay as raw strings here
 * - {@code ChatController} parses them into {@code DocumentId}/{@code DocumentVersionId}, which
 * is where malformed-id validation already lives.
 */
record ChatFilterRequest(String documentId, String documentVersionId, DocumentType documentType,
        DocumentClassification documentClassification, Integer page, String section) {
}

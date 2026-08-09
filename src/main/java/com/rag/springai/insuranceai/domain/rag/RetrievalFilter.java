package com.rag.springai.insuranceai.domain.rag;

import com.rag.springai.insuranceai.domain.document.DocumentClassification;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentType;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;

/**
 * Optional metadata restrictions applied to hybrid retrieval (brief FASE 6 section 6/7). Every
 * field is nullable - {@code null} means "no restriction on this field", never "match nothing".
 * Reuses the existing Document Management ubiquitous language ({@link DocumentType}, {@link
 * DocumentClassification}) rather than inventing new filter vocabulary.
 *
 * <p>A plain domain value object, not a SQL fragment: adapters ({@code
 * PgVectorStoreAdapter}/{@code PostgresLexicalSearchAdapter}) are responsible for translating it
 * into their own {@code WHERE} conditions - {@code application} never builds SQL itself.
 */
public record RetrievalFilter(DocumentId documentId, DocumentVersionId documentVersionId, DocumentType documentType,
        DocumentClassification documentClassification, Integer page, String section) {

    private static final RetrievalFilter NONE = new RetrievalFilter(null, null, null, null, null, null);

    public static RetrievalFilter none() {
        return NONE;
    }

    public boolean isEmpty() {
        return this.equals(NONE);
    }
}

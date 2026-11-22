package com.rag.springai.insuranceai.adapters.shared.persistence;

import com.rag.springai.insuranceai.domain.rag.RetrievalFilter;

import java.util.List;

/**
 * Translates a {@link RetrievalFilter} into parameterized {@code vector_store.metadata} {@code
 * WHERE} conditions (brief FASE 6 section 7: "Application: RetrievalFilter. Adapter: SQL WHERE
 * conditions. Never: Application -&gt; SQL"). Shared by {@code PgVectorStoreAdapter} and {@code
 * PostgresLexicalSearchAdapter} - both retrieval branches must honor the same filter fields the
 * same way, or hybrid fusion would silently combine differently-filtered candidate sets.
 *
 * <p>Every value is bound as a JDBC parameter, never string-concatenated into the SQL text -
 * {@code query}/{@code question} text is the only untrusted input in this pipeline and it never
 * reaches this class.
 */
public final class RetrievalFilterSql {

    private RetrievalFilterSql() {
    }

    /**
     * Appends {@code AND metadata ->> 'field' = ?} conditions (one per non-null filter field) to
     * {@code sql} and the matching values, in the same order, to {@code params}.
     */
    public static void appendConditions(StringBuilder sql, List<Object> params, RetrievalFilter filter) {
        if (filter.documentId() != null) {
            sql.append(" AND metadata ->> 'documentId' = ?");
            params.add(filter.documentId().toString());
        }
        if (filter.documentVersionId() != null) {
            sql.append(" AND metadata ->> 'documentVersionId' = ?");
            params.add(filter.documentVersionId().toString());
        }
        if (filter.documentType() != null) {
            sql.append(" AND metadata ->> 'documentType' = ?");
            params.add(filter.documentType().name());
        }
        if (filter.documentClassification() != null) {
            sql.append(" AND metadata ->> 'documentClassification' = ?");
            params.add(filter.documentClassification().name());
        }
        if (filter.page() != null) {
            sql.append(" AND (metadata ->> 'page')::int = ?");
            params.add(filter.page());
        }
        if (filter.section() != null) {
            sql.append(" AND metadata ->> 'section' = ?");
            params.add(filter.section());
        }
    }
}

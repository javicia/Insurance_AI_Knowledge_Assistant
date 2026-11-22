package com.rag.springai.insuranceai;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Truncates every table an integration test can write to, before each test method (brief
 * "Testcontainers Stability" audit, section 5: "compartir PostgreSQL no significa compartir
 * estado entre tests"). The single source of truth for test data isolation across the whole
 * suite - see {@code docs/testing/TESTCONTAINERS.md}.
 *
 * <p>Required because {@link TestcontainersConfiguration}'s containers are reused
 * ({@code withReuse(true)}) both across test classes within one Maven invocation (via Spring's
 * {@code ApplicationContext} cache) <em>and</em> across separate Maven invocations (via
 * Testcontainers' own reuse mechanism) - the database is therefore never guaranteed to start
 * empty. Full-schema {@code TRUNCATE ... CASCADE} rather than per-class ad-hoc {@code DELETE}
 * statements: one statement, correct regardless of foreign-key direction, safe to run even for
 * test classes that do not write to every table.
 *
 * <p><b>Deliberately excludes</b> the FASE 9 governance tables ({@code ai_systems}, {@code
 * ai_models}, {@code prompts}, {@code risk_assessments}, {@code ai_audit_records}): {@code
 * ai_systems}/{@code prompts} hold {@code V5__ai_governance.sql}'s seed data (the one AI system
 * and its ACTIVE prompt) that {@code AskInsuranceKnowledgeUseCase} genuinely depends on for
 * every grounded-answer request across the whole suite - truncating them would break every
 * other real-Postgres RAG integration test, not just isolate this one. {@code ai_audit_records}
 * is append-only log data looked up by a fresh random {@code traceId} per test, so it has no
 * collision risk and is left to accumulate, matching how a real audit trail behaves. FASE 10's
 * {@code evaluation_runs}/{@code evaluation_case_results} are excluded for the same reason as
 * {@code ai_audit_records}: append-only, looked up by a freshly generated {@code EvaluationRunId}
 * per run, no cross-test collision risk.
 */
public class DatabaseCleanupExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        JdbcTemplate jdbcTemplate = SpringExtension.getApplicationContext(context).getBean(JdbcTemplate.class);
        jdbcTemplate.execute(
                "TRUNCATE TABLE documents, document_versions, document_version_contents, document_chunks, "
                        + "vector_store RESTART IDENTITY CASCADE");
    }
}

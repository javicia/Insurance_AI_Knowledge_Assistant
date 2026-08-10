package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.domain.evaluation.EvaluationCaseResult;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationMetrics;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRun;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRunId;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRunStatus;
import com.rag.springai.insuranceai.domain.evaluation.ExpectedOutcome;
import com.rag.springai.insuranceai.ports.outbound.EvaluationRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcEvaluationRunRepository implements EvaluationRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcEvaluationRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void save(EvaluationRun run) {
        EvaluationMetrics metrics = run.metrics();
        jdbcTemplate.update("""
                INSERT INTO evaluation_runs (id, dataset_name, started_at, completed_at, total_cases,
                    outcome_accuracy, grounding_rate, no_answer_accuracy, recall_at_k, mrr, citation_coverage,
                    status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                run.id().value(), run.datasetName(), Timestamp.from(run.startedAt()),
                Timestamp.from(run.completedAt()), metrics.totalCases(), metrics.outcomeAccuracy(),
                metrics.groundingRate(), metrics.noAnswerAccuracy(), metrics.recallAtK(), metrics.mrr(),
                metrics.citationCoverage(), run.status().name());

        List<EvaluationCaseResult> results = run.results();
        List<Object[]> batchArgs = new java.util.ArrayList<>();
        for (int position = 0; position < results.size(); position++) {
            EvaluationCaseResult result = results.get(position);
            batchArgs.add(new Object[] { UUID.randomUUID(), run.id().value(), position, result.caseId(),
                    result.category(), result.expectedOutcome().name(), result.actualOutcome().name(),
                    result.outcomeMatch(), result.sourceHit(), result.reciprocalRank(), result.citationCount(),
                    result.traceId() });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO evaluation_case_results (id, run_id, position, case_id, category, expected_outcome,
                    actual_outcome, outcome_match, source_hit, reciprocal_rank, citation_count, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batchArgs);
    }

    @Override
    public Optional<EvaluationRun> findById(EvaluationRunId id) {
        return jdbcTemplate.query("SELECT * FROM evaluation_runs WHERE id = ?", this::mapRun, id.value()).stream()
                .findFirst();
    }

    @Override
    public List<EvaluationRun> findRecent(int limit) {
        return jdbcTemplate.query("SELECT * FROM evaluation_runs ORDER BY started_at DESC LIMIT ?", this::mapRun,
                limit);
    }

    private EvaluationRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        EvaluationRunId id = new EvaluationRunId(rs.getObject("id", UUID.class));
        EvaluationMetrics metrics = new EvaluationMetrics(rs.getInt("total_cases"), rs.getDouble("outcome_accuracy"),
                rs.getDouble("grounding_rate"), rs.getDouble("no_answer_accuracy"), rs.getDouble("recall_at_k"),
                rs.getDouble("mrr"), rs.getDouble("citation_coverage"));
        List<EvaluationCaseResult> results = jdbcTemplate.query(
                "SELECT * FROM evaluation_case_results WHERE run_id = ? ORDER BY position", this::mapCaseResult,
                id.value());
        return new EvaluationRun(id, rs.getString("dataset_name"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant(), results, metrics,
                EvaluationRunStatus.valueOf(rs.getString("status")));
    }

    private EvaluationCaseResult mapCaseResult(ResultSet rs, int rowNum) throws SQLException {
        Boolean sourceHit = (Boolean) rs.getObject("source_hit");
        Double reciprocalRank = (Double) rs.getObject("reciprocal_rank");
        return new EvaluationCaseResult(rs.getString("case_id"), rs.getString("category"),
                ExpectedOutcome.valueOf(rs.getString("expected_outcome")),
                ExpectedOutcome.valueOf(rs.getString("actual_outcome")), rs.getBoolean("outcome_match"), sourceHit,
                reciprocalRank, rs.getInt("citation_count"), rs.getString("trace_id"));
    }
}

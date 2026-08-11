package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.audit.AuditOutcome;
import com.rag.springai.insuranceai.domain.audit.AuditRecord;
import com.rag.springai.insuranceai.domain.audit.AuditRecordId;
import com.rag.springai.insuranceai.domain.rag.RetrievalOutcome;
import com.rag.springai.insuranceai.ports.outbound.AuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcAuditRepository implements AuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(AuditRecord record) {
        jdbcTemplate.update("""
                INSERT INTO ai_audit_records (id, trace_id, occurred_at, ai_system_id, provider, prompt_key,
                    prompt_version, retrieval_outcome, semantic_candidate_count, lexical_candidate_count,
                    final_candidate_count, grounding_status, prompt_injection_detected, pii_detected_in_question,
                    pii_detected_in_answer, latency_ms, outcome, error_classification)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id().value(), record.traceId(), Timestamp.from(record.timestamp()),
                record.aiSystemId().value(), record.provider(), record.promptKey(), record.promptVersion(),
                record.retrievalOutcome() != null ? record.retrievalOutcome().name() : null,
                record.semanticCandidateCount(), record.lexicalCandidateCount(), record.finalCandidateCount(),
                record.groundingStatus(), record.promptInjectionDetected(), record.piiDetectedInQuestion(),
                record.piiDetectedInAnswer(), record.latencyMs(), record.outcome().name(),
                record.errorClassification());
    }

    @Override
    public Optional<AuditRecord> findByTraceId(String traceId) {
        return jdbcTemplate
                .query("SELECT * FROM ai_audit_records WHERE trace_id = ? ORDER BY occurred_at DESC LIMIT 1",
                        this::mapRow, traceId)
                .stream()
                .findFirst();
    }

    @Override
    public List<AuditRecord> findRecent(int limit) {
        return jdbcTemplate.query("SELECT * FROM ai_audit_records ORDER BY occurred_at DESC LIMIT ?", this::mapRow,
                limit);
    }

    private AuditRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        String retrievalOutcome = rs.getString("retrieval_outcome");
        Integer promptVersion = (Integer) rs.getObject("prompt_version");
        return new AuditRecord(new AuditRecordId(rs.getObject("id", UUID.class)), rs.getString("trace_id"),
                rs.getTimestamp("occurred_at").toInstant(), new AiSystemId(rs.getObject("ai_system_id", UUID.class)),
                rs.getString("provider"), rs.getString("prompt_key"), promptVersion,
                retrievalOutcome != null ? RetrievalOutcome.valueOf(retrievalOutcome) : null,
                rs.getInt("semantic_candidate_count"), rs.getInt("lexical_candidate_count"),
                rs.getInt("final_candidate_count"), rs.getString("grounding_status"),
                rs.getBoolean("prompt_injection_detected"), rs.getBoolean("pii_detected_in_question"),
                rs.getBoolean("pii_detected_in_answer"), rs.getLong("latency_ms"),
                AuditOutcome.valueOf(rs.getString("outcome")), rs.getString("error_classification"));
    }
}

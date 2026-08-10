package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.aisystem.RiskClassification;
import com.rag.springai.insuranceai.domain.governance.RiskAssessment;
import com.rag.springai.insuranceai.domain.governance.RiskAssessmentId;
import com.rag.springai.insuranceai.domain.governance.RiskAssessmentStatus;
import com.rag.springai.insuranceai.ports.outbound.RiskAssessmentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcRiskAssessmentRepository implements RiskAssessmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRiskAssessmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(RiskAssessment riskAssessment) {
        jdbcTemplate.update("""
                INSERT INTO risk_assessments (id, ai_system_id, classification, rationale, controls,
                    residual_risk, reviewer, assessment_date, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status
                """,
                riskAssessment.id().value(), riskAssessment.aiSystemId().value(),
                riskAssessment.classification().name(), riskAssessment.rationale(), riskAssessment.controls(),
                riskAssessment.residualRisk(), riskAssessment.reviewer(),
                Timestamp.from(riskAssessment.assessmentDate()), riskAssessment.status().name());
    }

    @Override
    public Optional<RiskAssessment> findById(RiskAssessmentId id) {
        return jdbcTemplate.query("SELECT * FROM risk_assessments WHERE id = ?", this::mapRow, id.value())
                .stream()
                .findFirst();
    }

    @Override
    public List<RiskAssessment> findByAiSystemId(AiSystemId aiSystemId) {
        return jdbcTemplate.query("SELECT * FROM risk_assessments WHERE ai_system_id = ? ORDER BY assessment_date",
                this::mapRow, aiSystemId.value());
    }

    private RiskAssessment mapRow(ResultSet rs, int rowNum) throws SQLException {
        return RiskAssessment.reconstitute(new RiskAssessmentId(rs.getObject("id", UUID.class)),
                new AiSystemId(rs.getObject("ai_system_id", UUID.class)),
                RiskClassification.valueOf(rs.getString("classification")), rs.getString("rationale"),
                rs.getString("controls"), rs.getString("residual_risk"), rs.getString("reviewer"),
                rs.getTimestamp("assessment_date").toInstant(),
                RiskAssessmentStatus.valueOf(rs.getString("status")));
    }
}

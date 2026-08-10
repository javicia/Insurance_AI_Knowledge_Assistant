package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.domain.aisystem.AiSystem;
import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.aisystem.AiSystemStatus;
import com.rag.springai.insuranceai.domain.aisystem.HumanOversightRequirement;
import com.rag.springai.insuranceai.domain.aisystem.RiskClassification;
import com.rag.springai.insuranceai.ports.outbound.AiSystemRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC implementation of {@link AiSystemRepository} - see {@code JdbcDocumentRepository}'s Javadoc for why plain JDBC. */
@Component
public class JdbcAiSystemRepository implements AiSystemRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiSystemRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(AiSystem aiSystem) {
        HumanOversightRequirement oversight = aiSystem.humanOversight();
        jdbcTemplate.update("""
                INSERT INTO ai_systems (id, name, purpose, owner, intended_use, prohibited_use,
                    risk_classification, status, human_oversight_required, human_oversight_when_required,
                    human_oversight_escalation_condition, human_oversight_decision_responsibility)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET risk_classification = EXCLUDED.risk_classification,
                    status = EXCLUDED.status, updated_at = now()
                """,
                aiSystem.id().value(), aiSystem.name(), aiSystem.purpose(), aiSystem.owner(),
                aiSystem.intendedUse(), aiSystem.prohibitedUse(), aiSystem.riskClassification().name(),
                aiSystem.status().name(), oversight.required(), oversight.whenRequired(),
                oversight.escalationCondition(), oversight.decisionResponsibility());
    }

    @Override
    public Optional<AiSystem> findById(AiSystemId id) {
        return jdbcTemplate.query("SELECT * FROM ai_systems WHERE id = ?", this::mapRow, id.value())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<AiSystem> findByName(String name) {
        return jdbcTemplate.query("SELECT * FROM ai_systems WHERE name = ?", this::mapRow, name)
                .stream()
                .findFirst();
    }

    @Override
    public List<AiSystem> findAll() {
        return jdbcTemplate.query("SELECT * FROM ai_systems ORDER BY created_at", this::mapRow);
    }

    private AiSystem mapRow(ResultSet rs, int rowNum) throws SQLException {
        HumanOversightRequirement oversight = new HumanOversightRequirement(
                rs.getBoolean("human_oversight_required"), rs.getString("human_oversight_when_required"),
                rs.getString("human_oversight_escalation_condition"),
                rs.getString("human_oversight_decision_responsibility"));
        return AiSystem.reconstitute(new AiSystemId(rs.getObject("id", UUID.class)), rs.getString("name"),
                rs.getString("purpose"), rs.getString("owner"), rs.getString("intended_use"),
                rs.getString("prohibited_use"), RiskClassification.valueOf(rs.getString("risk_classification")),
                AiSystemStatus.valueOf(rs.getString("status")), oversight);
    }
}

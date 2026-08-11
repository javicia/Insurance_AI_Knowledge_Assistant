package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.model.AiModel;
import com.rag.springai.insuranceai.domain.model.ModelId;
import com.rag.springai.insuranceai.domain.model.ModelStatus;
import com.rag.springai.insuranceai.ports.outbound.ModelRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcModelRepository implements ModelRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcModelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(AiModel model) {
        jdbcTemplate.update("""
                INSERT INTO ai_models (id, ai_system_id, provider, model_identifier, version, capabilities,
                    intended_purpose, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status
                """,
                model.id().value(), model.aiSystemId().value(), model.provider(), model.modelIdentifier(),
                model.version(), model.capabilities(), model.intendedPurpose(), model.status().name());
    }

    @Override
    public Optional<AiModel> findById(ModelId id) {
        return jdbcTemplate.query("SELECT * FROM ai_models WHERE id = ?", this::mapRow, id.value())
                .stream()
                .findFirst();
    }

    @Override
    public List<AiModel> findByAiSystemId(AiSystemId aiSystemId) {
        return jdbcTemplate.query("SELECT * FROM ai_models WHERE ai_system_id = ? ORDER BY created_at",
                this::mapRow, aiSystemId.value());
    }

    private AiModel mapRow(ResultSet rs, int rowNum) throws SQLException {
        return AiModel.reconstitute(new ModelId(rs.getObject("id", UUID.class)),
                new AiSystemId(rs.getObject("ai_system_id", UUID.class)), rs.getString("provider"),
                rs.getString("model_identifier"), rs.getString("version"), rs.getString("capabilities"),
                rs.getString("intended_purpose"), ModelStatus.valueOf(rs.getString("status")));
    }
}

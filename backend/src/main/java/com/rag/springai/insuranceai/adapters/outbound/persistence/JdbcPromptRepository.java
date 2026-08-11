package com.rag.springai.insuranceai.adapters.outbound.persistence;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.prompt.Prompt;
import com.rag.springai.insuranceai.domain.prompt.PromptId;
import com.rag.springai.insuranceai.domain.prompt.PromptStatus;
import com.rag.springai.insuranceai.ports.outbound.PromptRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcPromptRepository implements PromptRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPromptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(Prompt prompt) {
        jdbcTemplate.update("""
                INSERT INTO prompts (id, ai_system_id, prompt_key, version, content, checksum, status,
                    effective_date, author, change_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status
                """,
                prompt.id().value(), prompt.aiSystemId().value(), prompt.promptKey(), prompt.version(),
                prompt.content(), prompt.checksum(), prompt.status().name(),
                Timestamp.from(prompt.effectiveDate()), prompt.author(), prompt.changeReason());
    }

    @Override
    public Optional<Prompt> findById(PromptId id) {
        return jdbcTemplate.query("SELECT * FROM prompts WHERE id = ?", this::mapRow, id.value())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Prompt> findActiveByKey(String promptKey) {
        return jdbcTemplate
                .query("SELECT * FROM prompts WHERE prompt_key = ? AND status = 'ACTIVE' ORDER BY version DESC LIMIT 1",
                        this::mapRow, promptKey)
                .stream()
                .findFirst();
    }

    @Override
    public List<Prompt> findByKey(String promptKey) {
        return jdbcTemplate.query("SELECT * FROM prompts WHERE prompt_key = ? ORDER BY version", this::mapRow,
                promptKey);
    }

    @Override
    public List<Prompt> findByAiSystemId(AiSystemId aiSystemId) {
        return jdbcTemplate.query("SELECT * FROM prompts WHERE ai_system_id = ? ORDER BY prompt_key, version",
                this::mapRow, aiSystemId.value());
    }

    private Prompt mapRow(ResultSet rs, int rowNum) throws SQLException {
        return Prompt.reconstitute(new PromptId(rs.getObject("id", UUID.class)),
                new AiSystemId(rs.getObject("ai_system_id", UUID.class)), rs.getString("prompt_key"),
                rs.getInt("version"), rs.getString("content"), PromptStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("effective_date").toInstant(), rs.getString("author"),
                rs.getString("change_reason"));
    }
}

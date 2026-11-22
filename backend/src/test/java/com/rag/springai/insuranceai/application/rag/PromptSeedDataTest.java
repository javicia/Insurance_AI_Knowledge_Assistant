package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.prompt.Prompt;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code V5__ai_governance.sql}'s seeded {@code insurance-rag-system-prompt} v1 row
 * stays consistent with {@link InsuranceRagSystemPrompt#TEXT} (brief FASE 9 section 24: the
 * Prompt Registry migration must genuinely reflect the FASE 5 constant it replaced, not an
 * approximation of it). If {@code InsuranceRagSystemPrompt.TEXT} is ever edited without updating
 * the migration's checksum, this test fails - the two are pure functions of each other
 * ({@code Prompt}'s constructor always recomputes SHA-256 from content, see its Javadoc), so
 * there is no way for them to silently drift without this test catching it.
 */
class PromptSeedDataTest {

    @Test
    void theMigrationsSeedChecksumMatchesTheCurrentConstantsChecksum() throws IOException {
        String computedChecksum = Prompt
                .draft(AiSystemId.generate(), "insurance-rag-system-prompt", 1, InsuranceRagSystemPrompt.TEXT, "test",
                        "test")
                .checksum();

        String migrationContent = Files.readString(
                new ClassPathResource("db/migration/V5__ai_governance.sql").getFile().toPath(),
                StandardCharsets.UTF_8);

        assertTrue(migrationContent.contains(computedChecksum),
                "V5__ai_governance.sql's seeded prompt checksum must match InsuranceRagSystemPrompt.TEXT's actual "
                        + "SHA-256 (" + computedChecksum + ") - update the migration if the constant changed");
    }
}

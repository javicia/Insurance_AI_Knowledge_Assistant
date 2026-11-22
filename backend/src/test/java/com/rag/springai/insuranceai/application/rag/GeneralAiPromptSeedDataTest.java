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
 * The {@code PromptSeedDataTest} equivalent for the general-AI fallback prompt (FASE 28):
 * {@code V9__general_ai_fallback_prompt.sql}'s seeded row must stay a faithful copy of {@link
 * GeneralAiSystemPrompt#TEXT}. The two are pure functions of each other ({@code Prompt} always
 * recomputes SHA-256 from content), so they cannot silently drift without this failing.
 *
 * <p>This matters more here than for the grounded prompt: the fallback prompt's content is the
 * control that forbids inventing citations and forbids asserting that a specific coverage
 * exists. If the constant were edited without the migration following, the deployed prompt would
 * no longer be the reviewed one, and nothing else in the system would notice.
 */
class GeneralAiPromptSeedDataTest {

    @Test
    void theMigrationsSeedChecksumMatchesTheCurrentConstantsChecksum() throws IOException {
        String computedChecksum = Prompt
                .draft(AiSystemId.generate(), "general-ai-system-prompt", 1, GeneralAiSystemPrompt.TEXT, "test",
                        "test")
                .checksum();

        String migrationContent = Files.readString(
                new ClassPathResource("db/migration/V9__general_ai_fallback_prompt.sql").getFile().toPath(),
                StandardCharsets.UTF_8);

        assertTrue(migrationContent.contains(computedChecksum),
                "V9__general_ai_fallback_prompt.sql's seeded prompt checksum must match GeneralAiSystemPrompt.TEXT's "
                        + "actual SHA-256 (" + computedChecksum + ") - update the migration if the constant changed");
    }

    @Test
    void thePromptForbidsCitationsAndCoverageClaims() {
        // Asserted as a requirement rather than left to review: these two prohibitions are the
        // reason GENERAL_AI_UNVERIFIED is safe to expose at all.
        String text = GeneralAiSystemPrompt.TEXT;

        assertTrue(text.contains("Never cite, quote, reference or invent a document"),
                "the fallback prompt must forbid citations - it has no sources");
        assertTrue(text.contains("Never state or imply that a particular coverage"),
                "the fallback prompt must forbid asserting that a specific coverage exists");
        assertTrue(text.contains("NOT based on the company's documentation"),
                "the fallback prompt must require the model to disclose that it is not using the documentation");
    }
}

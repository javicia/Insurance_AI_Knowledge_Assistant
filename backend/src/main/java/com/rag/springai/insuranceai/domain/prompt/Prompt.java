package com.rag.springai.insuranceai.domain.prompt;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Aggregate root of the Prompt Registry: versioned, auditable
 * prompt content, replacing the FASE 5 hardcoded {@code InsuranceRagSystemPrompt} constant (see
 * {@code AskInsuranceKnowledgeUseCase}, which now resolves the active prompt for {@code
 * promptKey} through {@code PromptRepository} instead of referencing that constant directly).
 *
 * <p>{@code promptKey} identifies a logical prompt slot (e.g. {@code
 * "insurance-rag-system-prompt"}); multiple {@link Prompt} rows can share a key, at most one
 * {@code ACTIVE} at a time - {@link #activate} is where that invariant would be enforced at the
 * use-case level (see {@code ActivatePromptUseCase}), not here, since "only one active per key"
 * is a cross-aggregate invariant this single aggregate cannot see by itself.
 */
public final class Prompt {

    private final PromptId id;
    private final AiSystemId aiSystemId;
    private final String promptKey;
    private final int version;
    private final String content;
    private final String checksum;
    private PromptStatus status;
    private final Instant effectiveDate;
    private final String author;
    private final String changeReason;

    private Prompt(PromptId id, AiSystemId aiSystemId, String promptKey, int version, String content,
            PromptStatus status, Instant effectiveDate, String author, String changeReason) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.aiSystemId = Objects.requireNonNull(aiSystemId, "aiSystemId must not be null");
        this.promptKey = requireNonBlank(promptKey, "promptKey");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        this.version = version;
        this.content = requireNonBlank(content, "content");
        this.checksum = sha256(content);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.effectiveDate = Objects.requireNonNull(effectiveDate, "effectiveDate must not be null");
        this.author = requireNonBlank(author, "author");
        this.changeReason = requireNonBlank(changeReason, "changeReason");
    }

    public static Prompt draft(AiSystemId aiSystemId, String promptKey, int version, String content, String author,
            String changeReason) {
        return new Prompt(PromptId.generate(), aiSystemId, promptKey, version, content, PromptStatus.DRAFT,
                Instant.now(), author, changeReason);
    }

    public static Prompt reconstitute(PromptId id, AiSystemId aiSystemId, String promptKey, int version,
            String content, PromptStatus status, Instant effectiveDate, String author, String changeReason) {
        return new Prompt(id, aiSystemId, promptKey, version, content, status, effectiveDate, author, changeReason);
    }

    public void activate() {
        status = PromptStatus.ACTIVE;
    }

    public void retire() {
        status = PromptStatus.RETIRED;
    }

    public PromptId id() {
        return id;
    }

    public AiSystemId aiSystemId() {
        return aiSystemId;
    }

    public String promptKey() {
        return promptKey;
    }

    public int version() {
        return version;
    }

    public String content() {
        return content;
    }

    public String checksum() {
        return checksum;
    }

    public PromptStatus status() {
        return status;
    }

    public Instant effectiveDate() {
        return effectiveDate;
    }

    public String author() {
        return author;
    }

    public String changeReason() {
        return changeReason;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", e);
        }
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Prompt other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

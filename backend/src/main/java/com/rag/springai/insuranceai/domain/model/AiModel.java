package com.rag.springai.insuranceai.domain.model;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;

import java.util.Objects;

/**
 * Aggregate root of the Model Registry (brief FASE 9 section 10): which provider/model
 * combinations are allowed to back an {@link com.rag.springai.insuranceai.domain.aisystem.AiSystem}
 * (an allow-list, brief section 13), independent of {@code EmbeddingModelPort}/{@code
 * LlmProvider}'s own runtime adapter selection - this is the governance record of the decision,
 * not the wiring mechanism itself.
 */
public final class AiModel {

    private final ModelId id;
    private final AiSystemId aiSystemId;
    private final String provider;
    private final String modelIdentifier;
    private final String version;
    private final String capabilities;
    private final String intendedPurpose;
    private ModelStatus status;

    private AiModel(ModelId id, AiSystemId aiSystemId, String provider, String modelIdentifier, String version,
            String capabilities, String intendedPurpose, ModelStatus status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.aiSystemId = Objects.requireNonNull(aiSystemId, "aiSystemId must not be null");
        this.provider = requireNonBlank(provider, "provider");
        this.modelIdentifier = requireNonBlank(modelIdentifier, "modelIdentifier");
        this.version = version;
        this.capabilities = requireNonBlank(capabilities, "capabilities");
        this.intendedPurpose = requireNonBlank(intendedPurpose, "intendedPurpose");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public static AiModel register(AiSystemId aiSystemId, String provider, String modelIdentifier, String version,
            String capabilities, String intendedPurpose) {
        return new AiModel(ModelId.generate(), aiSystemId, provider, modelIdentifier, version, capabilities,
                intendedPurpose, ModelStatus.ACTIVE);
    }

    public static AiModel reconstitute(ModelId id, AiSystemId aiSystemId, String provider, String modelIdentifier,
            String version, String capabilities, String intendedPurpose, ModelStatus status) {
        return new AiModel(id, aiSystemId, provider, modelIdentifier, version, capabilities, intendedPurpose, status);
    }

    public void deactivate() {
        status = ModelStatus.DEACTIVATED;
    }

    public ModelId id() {
        return id;
    }

    public AiSystemId aiSystemId() {
        return aiSystemId;
    }

    public String provider() {
        return provider;
    }

    public String modelIdentifier() {
        return modelIdentifier;
    }

    public String version() {
        return version;
    }

    public String capabilities() {
        return capabilities;
    }

    public String intendedPurpose() {
        return intendedPurpose;
    }

    public ModelStatus status() {
        return status;
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
        if (!(o instanceof AiModel other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

package com.rag.springai.insuranceai.domain.governance;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.aisystem.RiskClassification;

import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate root of Risk Assessment (brief FASE 9 section 10): the documented rationale behind
 * an {@link com.rag.springai.insuranceai.domain.aisystem.AiSystem}'s {@link RiskClassification},
 * not just the classification value itself. See {@code docs/governance/AI_ACT.md} for why this
 * is a self-assessment record, not a legal determination.
 */
public final class RiskAssessment {

    private final RiskAssessmentId id;
    private final AiSystemId aiSystemId;
    private final RiskClassification classification;
    private final String rationale;
    private final String controls;
    private final String residualRisk;
    private final String reviewer;
    private final Instant assessmentDate;
    private RiskAssessmentStatus status;

    private RiskAssessment(RiskAssessmentId id, AiSystemId aiSystemId, RiskClassification classification,
            String rationale, String controls, String residualRisk, String reviewer, Instant assessmentDate,
            RiskAssessmentStatus status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.aiSystemId = Objects.requireNonNull(aiSystemId, "aiSystemId must not be null");
        this.classification = Objects.requireNonNull(classification, "classification must not be null");
        this.rationale = requireNonBlank(rationale, "rationale");
        this.controls = requireNonBlank(controls, "controls");
        this.residualRisk = requireNonBlank(residualRisk, "residualRisk");
        this.reviewer = requireNonBlank(reviewer, "reviewer");
        this.assessmentDate = Objects.requireNonNull(assessmentDate, "assessmentDate must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public static RiskAssessment draft(AiSystemId aiSystemId, RiskClassification classification, String rationale,
            String controls, String residualRisk, String reviewer) {
        return new RiskAssessment(RiskAssessmentId.generate(), aiSystemId, classification, rationale, controls,
                residualRisk, reviewer, Instant.now(), RiskAssessmentStatus.DRAFT);
    }

    public static RiskAssessment reconstitute(RiskAssessmentId id, AiSystemId aiSystemId,
            RiskClassification classification, String rationale, String controls, String residualRisk,
            String reviewer, Instant assessmentDate, RiskAssessmentStatus status) {
        return new RiskAssessment(id, aiSystemId, classification, rationale, controls, residualRisk, reviewer,
                assessmentDate, status);
    }

    public void approve() {
        status = RiskAssessmentStatus.APPROVED;
    }

    public RiskAssessmentId id() {
        return id;
    }

    public AiSystemId aiSystemId() {
        return aiSystemId;
    }

    public RiskClassification classification() {
        return classification;
    }

    public String rationale() {
        return rationale;
    }

    public String controls() {
        return controls;
    }

    public String residualRisk() {
        return residualRisk;
    }

    public String reviewer() {
        return reviewer;
    }

    public Instant assessmentDate() {
        return assessmentDate;
    }

    public RiskAssessmentStatus status() {
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
        if (!(o instanceof RiskAssessment other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

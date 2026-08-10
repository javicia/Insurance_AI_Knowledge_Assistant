package com.rag.springai.insuranceai.domain.aisystem;

import java.util.Objects;

/**
 * Aggregate root of the AI System Registry (brief FASE 9 section 10): the first-class record of
 * what an AI system is for, who owns it, what it must never be used for, and what human
 * oversight it requires. This project registers exactly one: "Insurance Knowledge Assistant"
 * (see the FASE 9 Flyway seed data and {@code docs/governance/AI_GOVERNANCE.md}).
 */
public final class AiSystem {

    private final AiSystemId id;
    private final String name;
    private final String purpose;
    private final String owner;
    private final String intendedUse;
    private final String prohibitedUse;
    private RiskClassification riskClassification;
    private AiSystemStatus status;
    private final HumanOversightRequirement humanOversight;

    private AiSystem(AiSystemId id, String name, String purpose, String owner, String intendedUse,
            String prohibitedUse, RiskClassification riskClassification, AiSystemStatus status,
            HumanOversightRequirement humanOversight) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = requireNonBlank(name, "name");
        this.purpose = requireNonBlank(purpose, "purpose");
        this.owner = requireNonBlank(owner, "owner");
        this.intendedUse = requireNonBlank(intendedUse, "intendedUse");
        this.prohibitedUse = requireNonBlank(prohibitedUse, "prohibitedUse");
        this.riskClassification = Objects.requireNonNull(riskClassification, "riskClassification must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.humanOversight = Objects.requireNonNull(humanOversight, "humanOversight must not be null");
    }

    public static AiSystem register(String name, String purpose, String owner, String intendedUse,
            String prohibitedUse, RiskClassification riskClassification, HumanOversightRequirement humanOversight) {
        return new AiSystem(AiSystemId.generate(), name, purpose, owner, intendedUse, prohibitedUse,
                riskClassification, AiSystemStatus.DRAFT, humanOversight);
    }

    public static AiSystem reconstitute(AiSystemId id, String name, String purpose, String owner, String intendedUse,
            String prohibitedUse, RiskClassification riskClassification, AiSystemStatus status,
            HumanOversightRequirement humanOversight) {
        return new AiSystem(id, name, purpose, owner, intendedUse, prohibitedUse, riskClassification, status,
                humanOversight);
    }

    public void activate() {
        if (status == AiSystemStatus.RETIRED) {
            throw new IllegalStateException("a retired AI system cannot be reactivated - register a new one");
        }
        status = AiSystemStatus.ACTIVE;
    }

    public void retire() {
        status = AiSystemStatus.RETIRED;
    }

    public void reclassifyRisk(RiskClassification newClassification) {
        this.riskClassification = Objects.requireNonNull(newClassification, "newClassification must not be null");
    }

    public AiSystemId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String purpose() {
        return purpose;
    }

    public String owner() {
        return owner;
    }

    public String intendedUse() {
        return intendedUse;
    }

    public String prohibitedUse() {
        return prohibitedUse;
    }

    public RiskClassification riskClassification() {
        return riskClassification;
    }

    public AiSystemStatus status() {
        return status;
    }

    public HumanOversightRequirement humanOversight() {
        return humanOversight;
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
        if (!(o instanceof AiSystem other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

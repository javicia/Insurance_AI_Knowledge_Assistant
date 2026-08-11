package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

import com.rag.springai.insuranceai.domain.aisystem.AiSystem;
import com.rag.springai.insuranceai.domain.aisystem.HumanOversightRequirement;

record AiSystemResponse(String id, String name, String purpose, String owner, String intendedUse,
        String prohibitedUse, String riskClassification, String status, HumanOversightResponse humanOversight) {

    record HumanOversightResponse(boolean required, String whenRequired, String escalationCondition,
            String decisionResponsibility) {

        static HumanOversightResponse from(HumanOversightRequirement requirement) {
            return new HumanOversightResponse(requirement.required(), requirement.whenRequired(),
                    requirement.escalationCondition(), requirement.decisionResponsibility());
        }
    }

    static AiSystemResponse from(AiSystem aiSystem) {
        return new AiSystemResponse(aiSystem.id().toString(), aiSystem.name(), aiSystem.purpose(), aiSystem.owner(),
                aiSystem.intendedUse(), aiSystem.prohibitedUse(), aiSystem.riskClassification().name(),
                aiSystem.status().name(), HumanOversightResponse.from(aiSystem.humanOversight()));
    }
}

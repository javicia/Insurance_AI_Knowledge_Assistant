package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

import com.rag.springai.insuranceai.domain.aisystem.RiskClassification;

record RegisterAiSystemRequest(String name, String purpose, String owner, String intendedUse, String prohibitedUse,
        RiskClassification riskClassification, boolean humanOversightRequired, String humanOversightWhenRequired,
        String humanOversightEscalationCondition, String humanOversightDecisionResponsibility) {
}

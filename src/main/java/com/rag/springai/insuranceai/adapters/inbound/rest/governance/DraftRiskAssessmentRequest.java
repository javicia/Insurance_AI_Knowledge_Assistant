package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

import com.rag.springai.insuranceai.domain.aisystem.RiskClassification;

record DraftRiskAssessmentRequest(RiskClassification classification, String rationale, String controls,
        String residualRisk, String reviewer) {
}

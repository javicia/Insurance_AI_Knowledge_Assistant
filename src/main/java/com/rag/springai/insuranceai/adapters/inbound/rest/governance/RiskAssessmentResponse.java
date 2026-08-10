package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

import com.rag.springai.insuranceai.domain.governance.RiskAssessment;

record RiskAssessmentResponse(String id, String aiSystemId, String classification, String rationale,
        String controls, String residualRisk, String reviewer, String assessmentDate, String status) {

    static RiskAssessmentResponse from(RiskAssessment assessment) {
        return new RiskAssessmentResponse(assessment.id().toString(), assessment.aiSystemId().toString(),
                assessment.classification().name(), assessment.rationale(), assessment.controls(),
                assessment.residualRisk(), assessment.reviewer(), assessment.assessmentDate().toString(),
                assessment.status().name());
    }
}

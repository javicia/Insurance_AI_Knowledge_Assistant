package com.rag.springai.insuranceai.application.governance;

import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.governance.RiskAssessmentId;

public final class RiskAssessmentNotFoundException extends ApplicationException {

    public RiskAssessmentNotFoundException(RiskAssessmentId id) {
        super("RISK_ASSESSMENT_NOT_FOUND", "No risk assessment found with id " + id);
    }
}

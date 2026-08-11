package com.rag.springai.insuranceai.application.evaluation;

import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRunId;

public final class EvaluationRunNotFoundException extends ApplicationException {

    public EvaluationRunNotFoundException(EvaluationRunId id) {
        super("EVALUATION_RUN_NOT_FOUND", "No evaluation run found for id " + id);
    }
}

package com.rag.springai.insuranceai.application.governance;

import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.model.ModelId;

public final class ModelNotFoundException extends ApplicationException {

    public ModelNotFoundException(ModelId id) {
        super("MODEL_NOT_FOUND", "No registered model found with id " + id);
    }
}

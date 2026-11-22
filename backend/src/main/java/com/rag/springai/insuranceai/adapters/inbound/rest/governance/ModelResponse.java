package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

import com.rag.springai.insuranceai.domain.model.AiModel;

record ModelResponse(String id, String aiSystemId, String provider, String modelIdentifier, String version,
        String capabilities, String intendedPurpose, String status) {

    static ModelResponse from(AiModel model) {
        return new ModelResponse(model.id().toString(), model.aiSystemId().toString(), model.provider(),
                model.modelIdentifier(), model.version(), model.capabilities(), model.intendedPurpose(),
                model.status().name());
    }
}

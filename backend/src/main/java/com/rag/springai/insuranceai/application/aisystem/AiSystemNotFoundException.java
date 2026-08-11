package com.rag.springai.insuranceai.application.aisystem;

import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;

public final class AiSystemNotFoundException extends ApplicationException {

    public AiSystemNotFoundException(AiSystemId id) {
        super("AI_SYSTEM_NOT_FOUND", "AI system " + id + " was not found");
    }
}

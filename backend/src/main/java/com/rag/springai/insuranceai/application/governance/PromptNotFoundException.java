package com.rag.springai.insuranceai.application.governance;

import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;
import com.rag.springai.insuranceai.domain.prompt.PromptId;

public final class PromptNotFoundException extends ApplicationException {

    public PromptNotFoundException(PromptId id) {
        super("PROMPT_NOT_FOUND", "No prompt found with id " + id);
    }
}

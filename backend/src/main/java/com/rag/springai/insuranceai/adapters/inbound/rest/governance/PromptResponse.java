package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

import com.rag.springai.insuranceai.domain.prompt.Prompt;

record PromptResponse(String id, String aiSystemId, String promptKey, int version, String content, String checksum,
        String status, String effectiveDate, String author, String changeReason) {

    static PromptResponse from(Prompt prompt) {
        return new PromptResponse(prompt.id().toString(), prompt.aiSystemId().toString(), prompt.promptKey(),
                prompt.version(), prompt.content(), prompt.checksum(), prompt.status().name(),
                prompt.effectiveDate().toString(), prompt.author(), prompt.changeReason());
    }
}

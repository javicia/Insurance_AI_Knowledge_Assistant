package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

record DraftPromptRequest(String promptKey, String content, String author, String changeReason) {
}

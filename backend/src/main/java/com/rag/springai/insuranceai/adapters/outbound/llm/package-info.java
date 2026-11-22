/**
 * Outbound LLM adapters: OpenAI and Anthropic implementations of the {@code LlmProvider}
 * port (brief section 16), selected via {@code insurance-ai.ai.provider} configuration.
 * Neither the application layer nor any use case may depend on a vendor SDK directly.
 * FASE 7 (Multi Model) of {@code PROJECT_DISCOVERY.md}'s delivery plan was delivered early,
 * as part of FASE 5 (Basic RAG): both providers were needed from the first RAG use case
 * onward, so there was no reason to stub one out and revisit it later.
 */
package com.rag.springai.insuranceai.adapters.outbound.llm;

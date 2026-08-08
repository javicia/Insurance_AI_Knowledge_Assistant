/**
 * Outbound LLM adapters: OpenAI and Anthropic implementations of the {@code LlmProvider}
 * port (brief section 16), selected via {@code insurance-ai.ai.provider} configuration.
 * Neither the application layer nor any use case may depend on a vendor SDK directly.
 * Implemented starting FASE 7 (Multi Model) of the delivery plan in
 * {@code PROJECT_DISCOVERY.md}.
 */
package com.rag.springai.insuranceai.adapters.outbound.llm;

/**
 * Cross-cutting building blocks shared by multiple outbound LLM adapters. {@link
 * com.rag.springai.insuranceai.adapters.shared.llm.LlmMessageFormatter} is used by both {@code
 * OpenAiLlmAdapter} and {@code AnthropicLlmAdapter} - it deliberately does not live under {@code
 * adapters.outbound.llm} because it is a plain formatting helper, not itself a swappable
 * implementation of {@code LlmProvider} (see {@code ArchitectureTest.outboundAdaptersMustImplementAnOutboundPort}),
 * the same reasoning that already placed the Kafka event payloads in {@code adapters.shared.messaging}.
 */
package com.rag.springai.insuranceai.adapters.shared.llm;

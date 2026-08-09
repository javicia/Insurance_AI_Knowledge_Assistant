/**
 * Cross-cutting building blocks shared across adapters that are not themselves a swappable
 * port implementation - e.g. the Kafka event payloads in {@code adapters.shared.messaging}
 * (used by both inbound and outbound Kafka adapters) and the prompt-formatting helper in
 * {@code adapters.shared.llm} (used by multiple outbound {@code LlmProvider} adapters). Kept
 * out of {@code inbound}/{@code outbound} so {@code ArchitectureTest}'s "every adapter class
 * implements a port" rules stay meaningful. The technical exception hierarchy ({@code
 * InfrastructureException} and subtypes) used to live here (FASE 1) but moved to {@code
 * domain.shared.exception} in FASE 4 once the application layer also needed to catch it - see
 * that package's Javadoc.
 */
package com.rag.springai.insuranceai.adapters.shared;

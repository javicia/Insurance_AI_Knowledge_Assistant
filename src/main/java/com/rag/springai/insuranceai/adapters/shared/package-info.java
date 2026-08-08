/**
 * Cross-cutting building blocks shared by inbound and outbound adapters, such as the Kafka
 * event payloads in {@code adapters.shared.messaging}. Kept separate from
 * {@code inbound}/{@code outbound} because it is used by both. The technical exception
 * hierarchy ({@code InfrastructureException} and subtypes) used to live here (FASE 1) but
 * moved to {@code domain.shared.exception} in FASE 4 once the application layer also needed to
 * catch it - see that package's Javadoc.
 */
package com.rag.springai.insuranceai.adapters.shared;

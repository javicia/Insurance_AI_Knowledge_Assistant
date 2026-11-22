/**
 * Domain layer: entities, value objects, aggregates, domain services and domain events for
 * every bounded context of the Insurance Knowledge Assistant.
 *
 * <p>This layer is pure Java. It must not depend on Spring, Spring AI, Spring Data, JPA/
 * Hibernate, Kafka, Jackson, or any LLM vendor SDK — enforced by
 * {@code ArchitectureTest} in the test sources. See {@code docs/adr/ADR-001-HEXAGONAL-ARCHITECTURE.md}.
 */
package com.rag.springai.insuranceai.domain;

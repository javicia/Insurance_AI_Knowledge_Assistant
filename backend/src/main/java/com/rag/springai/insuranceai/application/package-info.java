/**
 * Application layer: use cases (commands/queries) that orchestrate domain objects through
 * ports. Depends only on {@code domain} and {@code ports} — never on {@code adapters} or
 * {@code infrastructure}, enforced by {@code ArchitectureTest}. See
 * {@code docs/adr/ADR-001-HEXAGONAL-ARCHITECTURE.md} for the accepted exception allowing
 * Spring wiring annotations (e.g. {@code @Service}) in this layer only.
 */
package com.rag.springai.insuranceai.application;

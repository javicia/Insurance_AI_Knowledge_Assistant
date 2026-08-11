/**
 * Ports: interfaces owned by the application/domain layers that describe how use cases talk
 * to the outside world, without depending on any concrete technology. {@code inbound} ports
 * are entry points driven by adapters (e.g. REST controllers); {@code outbound} ports are
 * exit points implemented by adapters (e.g. persistence, vector search, LLM providers).
 * Adapters must implement these interfaces explicitly rather than being called directly by
 * the application layer. See {@code docs/adr/ADR-001-HEXAGONAL-ARCHITECTURE.md}.
 */
package com.rag.springai.insuranceai.ports;

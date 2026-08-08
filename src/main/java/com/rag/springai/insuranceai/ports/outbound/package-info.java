/**
 * Outbound ports (driven side): interfaces such as repository, vector search, reranking,
 * LLM provider and messaging contracts, implemented by outbound adapters. Populated
 * incrementally as each bounded context is implemented (see {@code PROJECT_DISCOVERY.md}
 * delivery plan) - {@link com.rag.springai.insuranceai.ports.outbound.DocumentRepository} is
 * the first (FASE 3); no implementation exists yet, deferred to FASE 4.
 */
package com.rag.springai.insuranceai.ports.outbound;

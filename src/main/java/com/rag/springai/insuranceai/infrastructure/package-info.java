/**
 * Infrastructure: cross-cutting technical configuration that composes the application —
 * externalized configuration binding, observability (trace correlation, metrics) and
 * security wiring. This is the outermost layer: nothing else in the codebase may depend on
 * it, enforced by {@code ArchitectureTest}.
 */
package com.rag.springai.insuranceai.infrastructure;

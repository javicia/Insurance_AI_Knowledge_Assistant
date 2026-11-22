/**
 * Outbound persistence adapters: PostgreSQL/JDBC repository implementations of the domain
 * repository ports. Implemented starting FASE 3 (Document Domain) of the delivery plan in
 * {@code PROJECT_DISCOVERY.md}. Schema is managed by Flyway migrations (FASE 2), never by
 * ad-hoc SQL executed by the application.
 */
package com.rag.springai.insuranceai.adapters.outbound.persistence;

package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.security.SecurityEvent;

/**
 * Emits a {@link SecurityEvent} to wherever security events are collected (FASE 23). The
 * application layer depends only on this port, never on the concrete sink (structured JSON
 * logger today - see {@code infrastructure.security.SecurityEventLogger}), matching every other
 * outbound port in this codebase (ADR-001).
 */
public interface SecurityEventPort {

    void log(SecurityEvent event);
}

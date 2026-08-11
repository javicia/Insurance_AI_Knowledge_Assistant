package com.rag.springai.insuranceai.domain.security;

import java.util.Objects;

/**
 * One detected PII occurrence. {@code maskedValue} only - never the raw matched text (data
 * minimization, brief section 18/26): e.g. an email keeps its domain but masks the local part
 * ({@code j***@example.com}), enough for a human reviewer to sanity-check a detection without
 * this record itself becoming something that needs to be treated as sensitive data.
 */
public record PiiMatch(PiiType type, String maskedValue) {
    public PiiMatch {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(maskedValue, "maskedValue must not be null");
    }
}

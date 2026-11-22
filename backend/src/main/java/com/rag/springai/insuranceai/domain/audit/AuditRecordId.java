package com.rag.springai.insuranceai.domain.audit;

import java.util.Objects;
import java.util.UUID;

public record AuditRecordId(UUID value) {

    public AuditRecordId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static AuditRecordId generate() {
        return new AuditRecordId(UUID.randomUUID());
    }

    public static AuditRecordId of(String value) {
        return new AuditRecordId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

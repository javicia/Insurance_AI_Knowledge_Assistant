package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.audit.AuditRecord;

import java.util.List;
import java.util.Optional;

public interface AuditRepository {

    void save(AuditRecord record);

    Optional<AuditRecord> findByTraceId(String traceId);

    List<AuditRecord> findRecent(int limit);
}

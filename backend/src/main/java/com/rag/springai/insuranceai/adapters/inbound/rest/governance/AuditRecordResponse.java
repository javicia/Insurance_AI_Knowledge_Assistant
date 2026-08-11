package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

import com.rag.springai.insuranceai.domain.audit.AuditRecord;

record AuditRecordResponse(String id, String traceId, String timestamp, String aiSystemId, String provider,
        String promptKey, Integer promptVersion, String retrievalOutcome, int semanticCandidateCount,
        int lexicalCandidateCount, int finalCandidateCount, String groundingStatus, boolean promptInjectionDetected,
        boolean piiDetectedInQuestion, boolean piiDetectedInAnswer, long latencyMs, String outcome,
        String errorClassification) {

    static AuditRecordResponse from(AuditRecord record) {
        return new AuditRecordResponse(record.id().toString(), record.traceId(), record.timestamp().toString(),
                record.aiSystemId().toString(), record.provider(), record.promptKey(), record.promptVersion(),
                record.retrievalOutcome() != null ? record.retrievalOutcome().name() : null,
                record.semanticCandidateCount(), record.lexicalCandidateCount(), record.finalCandidateCount(),
                record.groundingStatus(), record.promptInjectionDetected(), record.piiDetectedInQuestion(),
                record.piiDetectedInAnswer(), record.latencyMs(), record.outcome().name(),
                record.errorClassification());
    }
}

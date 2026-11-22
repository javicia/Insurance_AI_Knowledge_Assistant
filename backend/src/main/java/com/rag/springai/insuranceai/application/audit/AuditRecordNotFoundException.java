package com.rag.springai.insuranceai.application.audit;

import com.rag.springai.insuranceai.application.shared.exception.ApplicationException;

public final class AuditRecordNotFoundException extends ApplicationException {

    public AuditRecordNotFoundException(String traceId) {
        super("AUDIT_RECORD_NOT_FOUND", "No audit record found for trace id " + traceId);
    }
}

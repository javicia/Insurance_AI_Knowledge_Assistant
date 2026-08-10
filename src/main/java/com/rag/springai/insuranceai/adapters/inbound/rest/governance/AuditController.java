package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

import com.rag.springai.insuranceai.application.audit.AuditRecordNotFoundException;
import com.rag.springai.insuranceai.application.audit.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** AI Audit lookup REST API (brief FASE 9 section 30). Read-only - records are written internally by {@code AskInsuranceKnowledgeUseCase}. */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "AI Audit", description = "Read-only lookup of the immutable per-request execution log. Never "
        + "exposes raw question/answer/chunk text - only identifiers, counts and flags (data minimization).")
class AuditController {

    private final AuditService auditService;

    AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/traces/{traceId}")
    @Operation(summary = "Get the audit record for one request",
            description = "traceId is the same identifier returned in every API response and logged via MDC - "
                    + "use it to look up exactly what happened for a given request.")
    AuditRecordResponse getByTraceId(@PathVariable String traceId) {
        return auditService.findByTraceId(traceId)
                .map(AuditRecordResponse::from)
                .orElseThrow(() -> new AuditRecordNotFoundException(traceId));
    }

    @GetMapping("/recent")
    @Operation(summary = "List the most recent audit records, newest first")
    List<AuditRecordResponse> recent(@RequestParam(defaultValue = "20") int limit) {
        return auditService.findRecent(limit).stream().map(AuditRecordResponse::from).toList();
    }
}

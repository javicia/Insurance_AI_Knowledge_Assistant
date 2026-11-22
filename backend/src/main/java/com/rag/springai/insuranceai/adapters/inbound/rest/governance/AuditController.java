package com.rag.springai.insuranceai.adapters.inbound.rest.governance;

import com.rag.springai.insuranceai.application.audit.AuditRecordNotFoundException;
import com.rag.springai.insuranceai.application.audit.AuditService;
import com.rag.springai.insuranceai.domain.security.SecurityEvent;
import com.rag.springai.insuranceai.domain.security.SecurityEventOutcome;
import com.rag.springai.insuranceai.domain.security.SecurityEventType;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import com.rag.springai.insuranceai.ports.outbound.SecurityEventPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final AuditService auditService;
    private final SecurityEventPort securityEventLogger;

    AuditController(AuditService auditService, SecurityEventPort securityEventLogger) {
        this.auditService = auditService;
        this.securityEventLogger = securityEventLogger;
    }

    @GetMapping("/traces/{traceId}")
    @Operation(summary = "Get the audit record for one request",
            description = "traceId is the same identifier returned in every API response and logged via MDC - "
                    + "use it to look up exactly what happened for a given request.")
    AuditRecordResponse getByTraceId(@PathVariable String traceId, HttpServletRequest request) {
        logAuditAccess(request);
        return auditService.findByTraceId(traceId)
                .map(AuditRecordResponse::from)
                .orElseThrow(() -> new AuditRecordNotFoundException(traceId));
    }

    @GetMapping("/recent")
    @Operation(summary = "List the most recent audit records, newest first")
    List<AuditRecordResponse> recent(@RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        logAuditAccess(request);
        return auditService.findRecent(limit).stream().map(AuditRecordResponse::from).toList();
    }

    /** Who looked up what audit record, and when, is itself security-relevant (brief FASE 23) -
     *  logged for every access attempt, success or not, since an {@link AuditRecordNotFoundException}
     *  still reveals that a given traceId was probed. */
    private void logAuditAccess(HttpServletRequest request) {
        String traceId = MDC.get(TraceId.MDC_KEY);
        if (traceId == null) {
            traceId = TraceId.generate().value();
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String principalId = authentication != null ? authentication.getName() : null;
        SecurityEvent event = SecurityEvent.now(SecurityEventType.AUDIT_ACCESS, SecurityEventOutcome.DETECTED,
                traceId, principalId, request.getMethod(), request.getRequestURI(), null, null);
        // Best-effort: a broken event sink must never turn a legitimate audit lookup into a 500 -
        // see SecurityErrorHandler's identical reasoning.
        try {
            securityEventLogger.log(event);
        }
        catch (RuntimeException e) {
            log.warn("Failed to emit AUDIT_ACCESS security event - continuing, since security event logging must "
                    + "never break the request it describes", e);
        }
    }
}

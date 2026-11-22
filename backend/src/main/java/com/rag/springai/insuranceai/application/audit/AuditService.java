package com.rag.springai.insuranceai.application.audit;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.audit.AuditOutcome;
import com.rag.springai.insuranceai.domain.audit.AuditRecord;
import com.rag.springai.insuranceai.domain.audit.AuditRecordId;
import com.rag.springai.insuranceai.domain.rag.RetrievalOutcome;
import com.rag.springai.insuranceai.ports.outbound.AuditRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Records and queries AI Audit trail events (brief FASE 9 section 10/18) - called once per
 * {@code POST /api/chat} execution, at the end of {@code AskInsuranceKnowledgeUseCase.ask}
 * (every branch: grounded answer, no-answer, blocked, or error - see that class).
 *
 * <p><b>Data minimization (brief section 18/26):</b> {@link #record} intentionally has no
 * parameter for the raw question, the raw answer, or retrieved chunk content - only counts,
 * flags and identifiers. See {@code AuditRecord}'s Javadoc.
 */
@Service
public class AuditService {

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
    }

    public void record(String traceId, AiSystemId aiSystemId, String provider, String promptKey,
            Integer promptVersion, RetrievalOutcome retrievalOutcome, int semanticCandidateCount,
            int lexicalCandidateCount, int finalCandidateCount, String groundingStatus,
            boolean promptInjectionDetected, boolean piiDetectedInQuestion, boolean piiDetectedInAnswer,
            long latencyMs, AuditOutcome outcome, String errorClassification) {
        AuditRecord record = new AuditRecord(AuditRecordId.generate(), traceId, Instant.now(), aiSystemId, provider,
                promptKey, promptVersion, retrievalOutcome, semanticCandidateCount, lexicalCandidateCount,
                finalCandidateCount, groundingStatus, promptInjectionDetected, piiDetectedInQuestion,
                piiDetectedInAnswer, latencyMs, outcome, errorClassification);
        auditRepository.save(record);
    }

    public Optional<AuditRecord> findByTraceId(String traceId) {
        return auditRepository.findByTraceId(traceId);
    }

    public List<AuditRecord> findRecent(int limit) {
        return auditRepository.findRecent(limit);
    }
}

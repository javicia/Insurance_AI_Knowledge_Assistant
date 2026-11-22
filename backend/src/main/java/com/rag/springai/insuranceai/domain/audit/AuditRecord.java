package com.rag.springai.insuranceai.domain.audit;

import com.rag.springai.insuranceai.domain.aisystem.AiSystemId;
import com.rag.springai.insuranceai.domain.rag.RetrievalOutcome;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable record of a single {@code POST /api/chat} execution (brief FASE 9 section 10/18)
 * - an aggregate root, but a pure fact once created (no behaviour beyond construction), created
 * by {@code RecordAuditUseCase} at the end of {@code AskInsuranceKnowledgeUseCase.ask}.
 *
 * <p>Deliberately does <b>not</b> store: the raw question text, retrieved chunk content, or the
 * generated answer text (data minimization, brief section 18 - "no registrar contenido sensible
 * innecesariamente"). {@code promptInjectionDetected}/{@code piiDetectedInQuestion}/{@code
 * piiDetectedInAnswer} capture *that* a guardrail fired, never the matched text itself - see
 * {@code docs/audit/AI_AUDIT.md}.
 *
 * <p>Per-chunk semantic/lexical/fusion/reranker scores (brief section 18's full list) are
 * intentionally summarized as counts here, not stored individually - a documented PoC
 * simplification (see {@code docs/audit/AI_AUDIT.md} limitations) rather than a full evaluation-
 * grade trace, which is FASE 10's job.
 */
public record AuditRecord(AuditRecordId id, String traceId, Instant timestamp, AiSystemId aiSystemId,
        String provider, String promptKey, Integer promptVersion, RetrievalOutcome retrievalOutcome,
        int semanticCandidateCount, int lexicalCandidateCount, int finalCandidateCount, String groundingStatus,
        boolean promptInjectionDetected, boolean piiDetectedInQuestion, boolean piiDetectedInAnswer, long latencyMs,
        AuditOutcome outcome, String errorClassification) {

    public AuditRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(aiSystemId, "aiSystemId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}

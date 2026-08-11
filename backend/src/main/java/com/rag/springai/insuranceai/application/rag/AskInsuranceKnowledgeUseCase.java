package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.application.audit.AuditService;
import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import com.rag.springai.insuranceai.application.document.exception.DocumentNotFoundException;
import com.rag.springai.insuranceai.application.document.exception.DocumentVersionNotFoundException;
import com.rag.springai.insuranceai.application.governance.WellKnownAiSystems;
import com.rag.springai.insuranceai.application.security.InputGuardAssessment;
import com.rag.springai.insuranceai.application.security.InputGuardService;
import com.rag.springai.insuranceai.domain.audit.AuditOutcome;
import com.rag.springai.insuranceai.domain.document.Document;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersion;
import com.rag.springai.insuranceai.domain.prompt.Prompt;
import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;
import com.rag.springai.insuranceai.domain.rag.LlmCompletion;
import com.rag.springai.insuranceai.domain.rag.LlmPrompt;
import com.rag.springai.insuranceai.domain.rag.RetrievalOutcome;
import com.rag.springai.insuranceai.domain.security.PromptInjectionAssessment;
import com.rag.springai.insuranceai.domain.security.SecurityEvent;
import com.rag.springai.insuranceai.domain.security.SecurityEventOutcome;
import com.rag.springai.insuranceai.domain.security.SecurityEventType;
import com.rag.springai.insuranceai.ports.outbound.DocumentRepository;
import com.rag.springai.insuranceai.ports.outbound.LlmProvider;
import com.rag.springai.insuranceai.ports.outbound.PromptRepository;
import com.rag.springai.insuranceai.ports.outbound.SecurityEventPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Answers an employee's question using only the ingested insurance documentation (brief
 * section 6): run the Advanced RAG hybrid retrieval pipeline ({@link HybridRetrievalService})
 * and - only if at least one final candidate clears its own retrieval branch's relevance bar -
 * ask the LLM, grounded strictly in that retrieved text (brief section 11: no relevant context
 * means no LLM call at all).
 *
 * <p><b>No-answer policy (FASE 6, brief section 21; FASE 14 audit remediation):</b> answer only
 * if at least one final candidate has {@code semanticScore >= insurance-ai.rag.semantic
 * .similarity-threshold} (FASE 5's original criterion, unchanged) <em>or</em> a non-null {@code
 * lexicalScore} that also clears {@code insurance-ai.rag.lexical.min-rank}. The lexical branch
 * originally treated any non-null {@code lexicalScore} as sufficient, on the reasoning that
 * PostgreSQL's {@code @@} text-search operator "only ever returns genuine matches" - true, but
 * "matched at all" and "matched well" are different things: {@code @@} is satisfied by a single
 * weak keyword overlap just as readily as a strong multi-term match, and {@code ts_rank_cd} was
 * being computed but never actually checked. {@code min-rank} closes that gap (default {@code
 * 0.0}, i.e. no behaviour change until a deployment tunes it against its own corpus - see {@code
 * InsuranceAiProperties.Rag.Lexical}'s Javadoc and {@code docs/adr/ADR-012-AUDIT-REMEDIATION.md}).
 * This deliberately never derives a probability from {@code fusionScore} or {@code rerankerScore}
 * (brief section 21/23: rank fusion and reranking are relevance signals, not grounding proof) -
 * it inspects each candidate's original semantic/lexical evidence, which reranking never
 * overwrites (see {@link HybridRetrievalResult#withRerankerScore}).
 *
 * <p><b>Guardrails (FASE 8, brief section 15):</b> {@link InputGuardService} scans the question
 * first - a detected prompt injection attempt blocks the request immediately ({@link
 * RagAnswer#blocked}), before retrieval or the LLM are ever invoked. Detected PII in the
 * question does not block (see {@code InputGuardService}'s Javadoc), only triggers a redacted
 * log line. Retrieved chunk content is separately scanned for injection phrasing
 * (observability only - see {@link #warnIfRetrievedContentContainsInjectionAttempts}) and the
 * generated answer is scanned for PII (observability only) - see {@code docs/security/SECURITY.md}.
 *
 * <p><b>Prompt Registry (FASE 9, brief section 24):</b> the system prompt is no longer the FASE
 * 5 {@code InsuranceRagSystemPrompt} hardcoded constant - it is fetched from {@link
 * PromptRepository#findActiveByKey} for {@code
 * WellKnownAiSystems#INSURANCE_RAG_SYSTEM_PROMPT_KEY}, seeded by {@code V5__ai_governance.sql}
 * with that exact constant's original text (see {@code PromptSeedDataTest}). There is no
 * fallback to the old constant: a missing active prompt is a real configuration error
 * ({@link IllegalStateException}), not silently patched over, so the Prompt Registry is
 * genuinely load-bearing rather than decorative.
 *
 * <p><b>AI Audit (FASE 9, brief section 18):</b> {@link AuditService#record} is called exactly
 * once per invocation, on every exit path (blocked, no-answer, grounded, or error) - see {@link
 * #ask}'s {@code finally}-equivalent structure. Only counts/flags/identifiers are recorded, never
 * the raw question, retrieved content, or answer text (data minimization - see {@code
 * AuditRecord}'s Javadoc).
 *
 * <p><b>Observability (FASE 11, brief section 55):</b> the LLM call is timed as {@code
 * rag.llm.latency} (tagged {@code provider}/{@code outcome}) via {@link MeterRegistry} - see
 * {@code docs/observability/OBSERVABILITY.md}. Retrieval's own latency is timed separately inside
 * {@link HybridRetrievalService}.
 *
 * <p>Knows nothing about OpenAI, Anthropic, {@code ChatClient}, {@code PgVectorStore}, {@code
 * tsvector}, JDBC or HTTP - only the ports/collaborators it depends on.
 */
@Service
public final class AskInsuranceKnowledgeUseCase {

    private static final Logger log = LoggerFactory.getLogger(AskInsuranceKnowledgeUseCase.class);

    private final HybridRetrievalService hybridRetrievalService;
    private final LlmProvider llmProvider;
    private final DocumentRepository documentRepository;
    private final InsuranceAiProperties properties;
    private final InputGuardService inputGuardService;
    private final PromptRepository promptRepository;
    private final AuditService auditService;
    private final MeterRegistry meterRegistry;
    private final SecurityEventPort securityEventLogger;

    public AskInsuranceKnowledgeUseCase(HybridRetrievalService hybridRetrievalService, LlmProvider llmProvider,
            DocumentRepository documentRepository, InsuranceAiProperties properties,
            InputGuardService inputGuardService, PromptRepository promptRepository, AuditService auditService,
            MeterRegistry meterRegistry, SecurityEventPort securityEventLogger) {
        this.hybridRetrievalService = Objects.requireNonNull(hybridRetrievalService,
                "hybridRetrievalService must not be null");
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.inputGuardService = Objects.requireNonNull(inputGuardService, "inputGuardService must not be null");
        this.promptRepository = Objects.requireNonNull(promptRepository, "promptRepository must not be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.securityEventLogger = Objects.requireNonNull(securityEventLogger, "securityEventLogger must not be null");
    }

    public RagAnswer ask(AskInsuranceKnowledgeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String traceId = command.traceId().value();
        String provider = properties.ai().provider().name().toLowerCase(java.util.Locale.ROOT);
        long startNanos = System.nanoTime();

        InputGuardAssessment inputAssessment = inputGuardService.assessQuestion(command.question());
        if (inputAssessment.blocked()) {
            log.warn("Blocked question due to prompt injection guardrail: patterns={}",
                    inputAssessment.promptInjection().matchedPatterns());
            securityEventLogger.log(SecurityEvent.now(SecurityEventType.PROMPT_INJECTION_BLOCKED,
                    SecurityEventOutcome.BLOCKED, traceId, currentPrincipalId(), null, null, null,
                    String.join(",", inputAssessment.promptInjection().matchedPatterns())));
            recordAudit(traceId, provider, null, null, null, 0, 0, 0, null, true,
                    inputAssessment.pii().detected(), false, startNanos, AuditOutcome.BLOCKED_BY_GUARDRAIL, null);
            return RagAnswer.blocked(traceId);
        }
        if (inputAssessment.pii().detected()) {
            log.info("Question contains detected PII (redacted): {}",
                    inputGuardService.sanitizeForLogging(command.question()));
            securityEventLogger.log(SecurityEvent.now(SecurityEventType.PII_DETECTED, SecurityEventOutcome.DETECTED,
                    traceId, currentPrincipalId(), null, null, null, "question"));
        }

        HybridRetrievalOutcome outcome;
        try {
            outcome = hybridRetrievalService.retrieve(command.question(), command.filter());
        }
        catch (RuntimeException e) {
            recordAudit(traceId, provider, null, null, null, 0, 0, 0, null, false,
                    inputAssessment.pii().detected(), false, startNanos, AuditOutcome.ERROR,
                    e.getClass().getSimpleName());
            throw e;
        }
        List<HybridRetrievalResult> finalCandidates = outcome.finalCandidates();

        if (!hasQualifyingCandidate(finalCandidates)) {
            recordAudit(traceId, provider, null, null, outcome.diagnostics().outcome(),
                    outcome.diagnostics().semanticCandidateCount(), outcome.diagnostics().lexicalCandidateCount(),
                    outcome.diagnostics().finalCandidateCount(), GroundingStatus.NOT_GROUNDED.name(), false,
                    inputAssessment.pii().detected(), false, startNanos, AuditOutcome.NO_ANSWER, null);
            return RagAnswer.noAnswer(traceId);
        }

        warnIfRetrievedContentContainsInjectionAttempts(finalCandidates);

        Prompt activePrompt;
        List<SourceReference> sources;
        try {
            activePrompt = promptRepository.findActiveByKey(WellKnownAiSystems.INSURANCE_RAG_SYSTEM_PROMPT_KEY)
                    .orElseThrow(() -> new IllegalStateException(
                            "No ACTIVE prompt found for key '" + WellKnownAiSystems.INSURANCE_RAG_SYSTEM_PROMPT_KEY
                                    + "' - the Prompt Registry must always have exactly one active prompt per key"));
            sources = buildSources(finalCandidates);
        }
        catch (RuntimeException e) {
            recordAudit(traceId, provider, null, null, outcome.diagnostics().outcome(),
                    outcome.diagnostics().semanticCandidateCount(), outcome.diagnostics().lexicalCandidateCount(),
                    outcome.diagnostics().finalCandidateCount(), null, false, inputAssessment.pii().detected(), false,
                    startNanos, AuditOutcome.ERROR, e.getClass().getSimpleName());
            throw e;
        }
        LlmPrompt prompt = new LlmPrompt(activePrompt.content(), command.question(),
                finalCandidates.stream().map(HybridRetrievalResult::content).toList());
        LlmCompletion completion;
        Timer.Sample llmSample = Timer.start(meterRegistry);
        try {
            completion = llmProvider.complete(prompt);
        }
        catch (RuntimeException e) {
            llmSample.stop(meterRegistry.timer("rag.llm.latency", "provider", provider, "outcome", "ERROR"));
            recordAudit(traceId, provider, activePrompt.promptKey(), activePrompt.version(),
                    outcome.diagnostics().outcome(), outcome.diagnostics().semanticCandidateCount(),
                    outcome.diagnostics().lexicalCandidateCount(), outcome.diagnostics().finalCandidateCount(), null,
                    false, inputAssessment.pii().detected(), false, startNanos, AuditOutcome.ERROR,
                    e.getClass().getSimpleName());
            throw e;
        }
        llmSample.stop(meterRegistry.timer("rag.llm.latency", "provider", provider, "outcome", "SUCCESS"));

        boolean piiInAnswer = inputGuardService.scanForPii(completion.text()).detected();
        if (piiInAnswer) {
            log.warn("Generated answer contains detected PII - review data minimization in source documents");
            securityEventLogger.log(SecurityEvent.now(SecurityEventType.PII_DETECTED, SecurityEventOutcome.DETECTED,
                    traceId, currentPrincipalId(), null, null, null, "answer"));
        }

        recordAudit(traceId, provider, activePrompt.promptKey(), activePrompt.version(),
                outcome.diagnostics().outcome(), outcome.diagnostics().semanticCandidateCount(),
                outcome.diagnostics().lexicalCandidateCount(), outcome.diagnostics().finalCandidateCount(),
                GroundingStatus.GROUNDED.name(), false, inputAssessment.pii().detected(), piiInAnswer, startNanos,
                AuditOutcome.GROUNDED_ANSWER, null);

        return new RagAnswer(completion.text(), sources, new Grounding(GroundingStatus.GROUNDED), traceId,
                piiInAnswer, false);
    }

    private void recordAudit(String traceId, String provider, String promptKey, Integer promptVersion,
            RetrievalOutcome retrievalOutcome, int semanticCandidateCount, int lexicalCandidateCount,
            int finalCandidateCount, String groundingStatus, boolean promptInjectionDetected,
            boolean piiDetectedInQuestion, boolean piiDetectedInAnswer, long startNanos, AuditOutcome outcome,
            String errorClassification) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        auditService.record(traceId, WellKnownAiSystems.INSURANCE_KNOWLEDGE_ASSISTANT, provider, promptKey,
                promptVersion, retrievalOutcome, semanticCandidateCount, lexicalCandidateCount, finalCandidateCount,
                groundingStatus, promptInjectionDetected, piiDetectedInQuestion, piiDetectedInAnswer, latencyMs,
                outcome, errorClassification);
    }

    private String currentPrincipalId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }

    private void warnIfRetrievedContentContainsInjectionAttempts(List<HybridRetrievalResult> candidates) {
        for (HybridRetrievalResult candidate : candidates) {
            PromptInjectionAssessment assessment = inputGuardService.scanForInjection(candidate.content());
            if (assessment.detected()) {
                log.warn(
                        "Retrieved chunk {} contains prompt-injection-like phrasing (patterns={}) - treated as "
                                + "untrusted data, never as instructions (see LlmMessageFormatter)",
                        candidate.chunkId(), assessment.matchedPatterns());
            }
        }
    }

    private boolean hasQualifyingCandidate(List<HybridRetrievalResult> candidates) {
        double semanticThreshold = properties.rag().semantic().similarityThreshold();
        double minLexicalRank = properties.rag().lexical().minRank();
        return candidates.stream()
                .anyMatch(candidate -> (candidate.semanticScore() != null
                        && candidate.semanticScore() >= semanticThreshold)
                        || (candidate.lexicalScore() != null && candidate.lexicalScore() >= minLexicalRank));
    }

    private List<SourceReference> buildSources(List<HybridRetrievalResult> candidates) {
        Map<DocumentId, Document> documentsById = new HashMap<>();
        List<SourceReference> sources = new ArrayList<>();

        for (HybridRetrievalResult candidate : candidates) {
            Document document = documentsById.computeIfAbsent(candidate.documentId(), id -> documentRepository
                    .findById(id)
                    .orElseThrow(() -> new DocumentNotFoundException(id)));
            DocumentVersion version = document.version(candidate.documentVersionId())
                    .orElseThrow(() -> new DocumentVersionNotFoundException(candidate.documentId(),
                            candidate.documentVersionId()));

            sources.add(new SourceReference(document.id().toString(), document.name(),
                    version.versionNumber().toString(), candidate.metadata().page(), candidate.metadata().section(),
                    candidate.chunkId().toString()));
        }
        return sources;
    }
}

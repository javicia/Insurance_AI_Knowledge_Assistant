package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.application.audit.AuditService;
import com.rag.springai.insuranceai.application.benchmark.LlmCost;
import com.rag.springai.insuranceai.application.benchmark.LlmCostCalculator;
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
import com.rag.springai.insuranceai.domain.rag.RetrievalDiagnostics;
import com.rag.springai.insuranceai.domain.rag.RetrievalOutcome;
import com.rag.springai.insuranceai.domain.security.PromptInjectionAssessment;
import com.rag.springai.insuranceai.domain.security.SecurityEvent;
import com.rag.springai.insuranceai.domain.security.SecurityEventOutcome;
import com.rag.springai.insuranceai.domain.security.SecurityEventType;
import com.rag.springai.insuranceai.domain.shared.exception.TransientProcessingException;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Answers an employee's question using only the ingested insurance documentation (brief
 * section 6): run the Advanced RAG hybrid retrieval pipeline ({@link HybridRetrievalService})
 * and - only if at least one final candidate clears its own retrieval branch's relevance bar -
 * ask the LLM, grounded strictly in that retrieved text (brief section 11: no relevant context
 * means no LLM call at all).
 *
 * <p><b>No-answer policy (FASE 6, brief section 21; FASE 14 audit remediation; FASE 28):</b>
 * answer only if at least one final candidate carries sufficient evidence - see {@link
 * #isSufficientEvidence}. This deliberately never derives a probability from {@code fusionScore}
 * or {@code rerankerScore} (brief section 21/23: rank fusion and reranking are relevance signals,
 * not grounding proof) - it inspects each candidate's original semantic/lexical evidence, which
 * reranking never overwrites (see {@link HybridRetrievalResult#withRerankerScore}). FASE 28
 * extended the same principle to the lexical branch itself: {@code ts_rank_cd} is a relevance
 * signal too, so it is no longer sufficient on its own either, and the branch must additionally
 * cover {@code insurance-ai.rag.lexical.min-term-coverage} of the question's lexemes.
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
 * <p><b>Benchmarking metrics (FASE 27):</b> the whole use case is timed as {@code
 * rag.request.latency} (tagged {@code outcome} with the same {@link AuditOutcome} the audit
 * record carries, so a metric and an audit row can never disagree about how a request ended),
 * and each outcome also increments its own counter - {@code rag.grounding.count}, {@code
 * rag.no_answer.count}, {@code rag.blocked.count}. {@code rag.pii.detected.count} is tagged
 * {@code where=question|answer}. All of these are emitted from {@link #recordAudit}/the same
 * call sites as their audit and security-event counterparts, deliberately: a future new exit
 * path physically cannot record one without the other. Token counters ({@code
 * rag.llm.tokens.input}/{@code .output}/{@code .total}, tagged {@code provider}) are emitted
 * only when the provider actually reported usage - never as zeros, which would make a cost
 * projection built on them silently wrong (see {@link LlmCompletion}).
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
    private final GeneralAiFallbackPolicy generalAiFallbackPolicy;
    private final GeneralAiBudgetLedger budgetLedger;
    private final LlmCostCalculator llmCostCalculator;
    private final QueryScopeClassifier queryScopeClassifier;

    public AskInsuranceKnowledgeUseCase(HybridRetrievalService hybridRetrievalService, LlmProvider llmProvider,
            DocumentRepository documentRepository, InsuranceAiProperties properties,
            InputGuardService inputGuardService, PromptRepository promptRepository, AuditService auditService,
            MeterRegistry meterRegistry, SecurityEventPort securityEventLogger,
            GeneralAiFallbackPolicy generalAiFallbackPolicy, GeneralAiBudgetLedger budgetLedger,
            LlmCostCalculator llmCostCalculator, QueryScopeClassifier queryScopeClassifier) {
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
        this.generalAiFallbackPolicy = Objects.requireNonNull(generalAiFallbackPolicy,
                "generalAiFallbackPolicy must not be null");
        this.budgetLedger = Objects.requireNonNull(budgetLedger, "budgetLedger must not be null");
        this.llmCostCalculator = Objects.requireNonNull(llmCostCalculator, "llmCostCalculator must not be null");
        this.queryScopeClassifier = Objects.requireNonNull(queryScopeClassifier,
                "queryScopeClassifier must not be null");
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
            logSecurityEventSafely(SecurityEvent.now(SecurityEventType.PROMPT_INJECTION_BLOCKED,
                    SecurityEventOutcome.BLOCKED, traceId, currentPrincipalId(), null, null, null,
                    String.join(",", inputAssessment.promptInjection().matchedPatterns())));
            recordAudit(traceId, provider, null, null, null, 0, 0, 0, null, true,
                    inputAssessment.pii().detected(), false, startNanos, AuditOutcome.BLOCKED_BY_GUARDRAIL, null);
            return RagAnswer.blocked(traceId);
        }
        if (inputAssessment.pii().detected()) {
            log.info("Question contains detected PII (redacted): {}",
                    inputGuardService.sanitizeForLogging(command.question()));
            meterRegistry.counter("rag.pii.detected.count", "where", "question").increment();
            logSecurityEventSafely(SecurityEvent.now(SecurityEventType.PII_DETECTED, SecurityEventOutcome.DETECTED,
                    traceId, currentPrincipalId(), null, null, null, "question"));
        }

        // Scope control (FASE 32), BEFORE retrieval and therefore before any evidence can be
        // weighed. Closes three of the four false groundings FASE 31 measured: a credential
        // request, a private-record lookup and an operation, each of which earned high similarity
        // because it genuinely talks about insurance. No threshold can separate them - FASE 30 and
        // 31 both measured that with different models and different thresholds - so this decision
        // is deliberately independent of every score in the pipeline.
        //
        // Runs before retrieval on purpose: text inside retrieved documents therefore cannot
        // influence it, and neither can query expansion.
        QueryScope scope = queryScopeClassifier.classify(command.question());
        if (!scope.allowsDocumentaryGrounding()) {
            meterRegistry.counter("rag.scope.refused.count", "scope", scope.name()).increment();
            log.info("Question is out of documentary scope (scope={}) - returning an explicit "
                    + "no-answer without retrieving or answering", scope);
            // NO_ANSWER, never BLOCKED: blocking is for an attack on the system, and an employee
            // asking to change a phone number is not attacking anything. The generic no-answer
            // message is also what keeps this safe - it cannot imply the operation was carried out.
            recordAudit(traceId, provider, null, null, null, 0, 0, 0,
                    GroundingStatus.NOT_GROUNDED.name(), false, inputAssessment.pii().detected(), false,
                    startNanos, AuditOutcome.NO_ANSWER, null);
            return RagAnswer.noAnswer(traceId);
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
            return answerWithoutDocumentaryEvidence(command, outcome, inputAssessment, traceId, provider, startNanos);
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
        recordTokenUsage(provider, completion);

        boolean piiInAnswer = inputGuardService.scanForPii(completion.text()).detected();
        if (piiInAnswer) {
            log.warn("Generated answer contains detected PII - review data minimization in source documents");
            meterRegistry.counter("rag.pii.detected.count", "where", "answer").increment();
            logSecurityEventSafely(SecurityEvent.now(SecurityEventType.PII_DETECTED, SecurityEventOutcome.DETECTED,
                    traceId, currentPrincipalId(), null, null, null, "answer"));
        }

        recordAudit(traceId, provider, activePrompt.promptKey(), activePrompt.version(),
                outcome.diagnostics().outcome(), outcome.diagnostics().semanticCandidateCount(),
                outcome.diagnostics().lexicalCandidateCount(), outcome.diagnostics().finalCandidateCount(),
                GroundingStatus.GROUNDED.name(), false, inputAssessment.pii().detected(), piiInAnswer, startNanos,
                AuditOutcome.GROUNDED_ANSWER, null);

        return new RagAnswer(completion.text(), sources, new Grounding(GroundingStatus.GROUNDED),
                AnswerMode.DOCUMENT_GROUNDED, traceId, piiInAnswer);
    }

    /**
     * The exit taken when retrieval produced no sufficient evidence (FASE 28): either the
     * explicit no-answer that has always been the brief's section 11 behaviour, or - if {@link
     * GeneralAiFallbackPolicy} permits it - an answer from the model's own general knowledge,
     * labelled {@link AnswerMode#GENERAL_AI_UNVERIFIED} and carrying no citations.
     *
     * <p>Reached only from the no-evidence branch, which is what structurally guarantees two
     * properties the fallback would be dangerous without: a blocked question can never arrive
     * here ({@link #ask} returns on the guardrail path before retrieval even runs), and a
     * question that <em>did</em> find evidence is never diverted to general knowledge. Neither
     * depends on a rule that could be edited out of the policy.
     *
     * <p>A provider failure on this path does not propagate as a 502/503. The grounded path has
     * no fallback for an LLM outage, so failing loudly is the only honest option there; here the
     * caller asked a question the documentation could not answer, and the correct degraded
     * result is the no-answer they would have received had the fallback been disabled - not an
     * error implying their question was answerable.
     */
    private RagAnswer answerWithoutDocumentaryEvidence(AskInsuranceKnowledgeCommand command,
            HybridRetrievalOutcome outcome, InputGuardAssessment inputAssessment, String traceId, String provider,
            long startNanos) {
        RetrievalDiagnostics diagnostics = outcome.diagnostics();
        boolean piiInQuestion = inputAssessment.pii().detected();

        Optional<GeneralAiFallbackRefusal> refusal = generalAiFallbackPolicy.evaluate(command.question(),
                piiInQuestion);
        if (refusal.isPresent()) {
            meterRegistry.counter("rag.general_ai.refused.count", "reason", refusal.get().name()).increment();
            log.info("General-AI fallback not used (reason={}) - returning an explicit no-answer", refusal.get());
            recordAudit(traceId, provider, null, null, diagnostics.outcome(), diagnostics.semanticCandidateCount(),
                    diagnostics.lexicalCandidateCount(), diagnostics.finalCandidateCount(),
                    GroundingStatus.NOT_GROUNDED.name(), false, piiInQuestion, false, startNanos,
                    AuditOutcome.NO_ANSWER, null);
            return RagAnswer.noAnswer(traceId);
        }

        // Budget is reserved BEFORE the provider is called (FASE 29, DEF-08): counting spend
        // afterwards cannot enforce a limit, because the money is gone by the time it is counted
        // and two concurrent requests both see headroom that only one of them can have.
        Optional<UUID> reservation = budgetLedger.reserve();
        if (reservation.isEmpty()) {
            meterRegistry.counter("rag.general_ai.refused.count", "reason",
                    GeneralAiFallbackRefusal.BUDGET_EXHAUSTED.name()).increment();
            log.warn("General-AI fallback refused: budget exhausted - returning an explicit no-answer");
            recordAudit(traceId, provider, null, null, diagnostics.outcome(), diagnostics.semanticCandidateCount(),
                    diagnostics.lexicalCandidateCount(), diagnostics.finalCandidateCount(),
                    GroundingStatus.NOT_GROUNDED.name(), false, piiInQuestion, false, startNanos,
                    AuditOutcome.NO_ANSWER, null);
            return RagAnswer.noAnswer(traceId);
        }
        UUID reservationId = reservation.get();

        Prompt activePrompt;
        try {
            activePrompt = promptRepository.findActiveByKey(WellKnownAiSystems.GENERAL_AI_SYSTEM_PROMPT_KEY)
                    .orElseThrow(() -> new IllegalStateException(
                            "No ACTIVE prompt found for key '" + WellKnownAiSystems.GENERAL_AI_SYSTEM_PROMPT_KEY
                                    + "' - the Prompt Registry must always have exactly one active prompt per key"));
        }
        catch (RuntimeException e) {
            // Nothing was sent to the provider, so the reservation is definitely unspent.
            budgetLedger.releaseAfterFailure(reservationId, false);
            throw e;
        }
        // Deliberately an empty passage list: the retrieved candidates were judged insufficient,
        // so passing them anyway would ground the answer in exactly the evidence this branch
        // just rejected, while the response claims to have no sources.
        LlmPrompt prompt = new LlmPrompt(activePrompt.content(), command.question(), List.of());

        LlmCompletion completion;
        Timer.Sample llmSample = Timer.start(meterRegistry);
        try {
            completion = llmProvider.complete(prompt);
        }
        catch (RuntimeException e) {
            llmSample.stop(meterRegistry.timer("rag.llm.latency", "provider", provider, "outcome", "ERROR"));
            meterRegistry.counter("rag.general_ai.failed.count", "error", e.getClass().getSimpleName()).increment();
            // A definite rejection cannot have been billed, so the reservation is returned. A
            // timeout is kept: the provider may have processed and billed it, and this side has
            // no way to find out - see GeneralAiBudgetLedger#releaseAfterFailure.
            budgetLedger.releaseAfterFailure(reservationId, isTimeout(e));
            log.warn("General-AI fallback provider call failed - degrading to an explicit no-answer", e);
            recordAudit(traceId, provider, activePrompt.promptKey(), activePrompt.version(), diagnostics.outcome(),
                    diagnostics.semanticCandidateCount(), diagnostics.lexicalCandidateCount(),
                    diagnostics.finalCandidateCount(), GroundingStatus.NOT_GROUNDED.name(), false, piiInQuestion,
                    false, startNanos, AuditOutcome.NO_ANSWER, e.getClass().getSimpleName());
            return RagAnswer.noAnswer(traceId);
        }
        llmSample.stop(meterRegistry.timer("rag.llm.latency", "provider", provider, "outcome", "SUCCESS"));
        recordTokenUsage(provider, completion);
        // Reconcile the worst-case reservation down to what was really consumed. An empty
        // Optional means the provider reported no usage, and the ledger then KEEPS the worst
        // case rather than refunding it - refunding would make an unmetered provider unlimited.
        budgetLedger.reconcile(reservationId,
                llmCostCalculator.observedCostOf(completion).map(LlmCost::totalCost));

        boolean piiInAnswer = inputGuardService.scanForPii(completion.text()).detected();
        if (piiInAnswer) {
            log.warn("General-AI answer contains detected PII");
            meterRegistry.counter("rag.pii.detected.count", "where", "answer").increment();
            logSecurityEventSafely(SecurityEvent.now(SecurityEventType.PII_DETECTED, SecurityEventOutcome.DETECTED,
                    traceId, currentPrincipalId(), null, null, null, "answer"));
        }

        meterRegistry.counter("rag.general_ai.count").increment();
        recordAudit(traceId, provider, activePrompt.promptKey(), activePrompt.version(), diagnostics.outcome(),
                diagnostics.semanticCandidateCount(), diagnostics.lexicalCandidateCount(),
                diagnostics.finalCandidateCount(), GroundingStatus.NOT_GROUNDED.name(), false, piiInQuestion,
                piiInAnswer, startNanos, AuditOutcome.GENERAL_AI_ANSWER, null);

        return RagAnswer.generalAi(completion.text(), traceId, piiInAnswer);
    }

    /**
     * Whether a provider failure was a timeout, which the budget ledger treats differently from
     * a definite rejection: a timed-out request may still have been processed and billed.
     * Recognised by the transient classification the LLM adapters already assign, rather than by
     * exception type - the vendor SDKs wrap their own timeouts inconsistently.
     */
    private static boolean isTimeout(RuntimeException e) {
        String text = (e.getMessage() == null ? "" : e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return e instanceof TransientProcessingException
                || text.contains("timeout") || text.contains("timed out");
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
        recordRequestMetrics(latencyMs, outcome);
    }

    /**
     * Emitted from {@link #recordAudit} rather than from each exit path, so the request timer and
     * the outcome counters share the audit's own "exactly once per invocation, on every exit
     * path" guarantee for free - including the two error paths that {@code throw} after
     * auditing, whose latency would otherwise never be measured at all.
     *
     * <p>The timer is fed the already-computed millisecond latency instead of a {@link
     * Timer.Sample}, so it measures exactly the same interval the audit record reports - two
     * different numbers for "how long did this request take" would be worse than one coarser one.
     */
    private void recordRequestMetrics(long latencyMs, AuditOutcome outcome) {
        meterRegistry.timer("rag.request.latency", "outcome", outcome.name())
                .record(latencyMs, TimeUnit.MILLISECONDS);
        switch (outcome) {
            case GROUNDED_ANSWER -> meterRegistry.counter("rag.grounding.count").increment();
            case NO_ANSWER -> meterRegistry.counter("rag.no_answer.count").increment();
            case BLOCKED_BY_GUARDRAIL -> meterRegistry.counter("rag.blocked.count").increment();
            // GENERAL_AI_ANSWER's counter (rag.general_ai.count) is incremented at its own call
            // site rather than here, deliberately: it must NOT contribute to rag.grounding.count,
            // which is the metric a dashboard reads as "answers backed by our documentation".
            // Folding an unverified answer into that number would overstate exactly the thing
            // this feature is designed to keep separate.
            case GENERAL_AI_ANSWER -> {
            }
            // ERROR intentionally has no dedicated counter: rag.request.latency's outcome=ERROR
            // tag already counts it, and a second, independently-incremented meter for the same
            // event is one more thing that can silently disagree with the first.
            case ERROR -> {
            }
        }
    }

    /**
     * Records the provider's reported token usage - and nothing at all when it reported none
     * ({@code FakeLlmAdapter} never does, and a real provider may omit it). Substituting zeros
     * would not be a harmless default: these counters are the observed input to {@code
     * LlmCostCalculator}, so a zero is indistinguishable from a free call and would understate
     * the measured cost of a benchmark run.
     */
    private void recordTokenUsage(String provider, LlmCompletion completion) {
        if (!completion.hasTokenUsage()) {
            return;
        }
        meterRegistry.counter("rag.llm.tokens.input", "provider", provider).increment(completion.inputTokens());
        meterRegistry.counter("rag.llm.tokens.output", "provider", provider).increment(completion.outputTokens());
        meterRegistry.counter("rag.llm.tokens.total", "provider", provider).increment(completion.totalTokens());
    }

    private String currentPrincipalId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }

    /**
     * Security event logging is best-effort observability, never a precondition for answering a
     * real question - a legitimate RAG request must complete even if the event sink itself is
     * broken. Any exception from {@link SecurityEventPort#log} is caught here and demoted to a
     * plain {@code WARN} on this class's own logger, never the {@code "security-events"} logger -
     * swallowing it there too would risk masking a real sink failure as a normal security event.
     */
    private void logSecurityEventSafely(SecurityEvent event) {
        try {
            securityEventLogger.log(event);
        }
        catch (RuntimeException e) {
            log.warn("Failed to emit security event of type {} - continuing, since security event logging must "
                    + "never break the request it describes", event.type(), e);
        }
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

    /**
     * The evidence-sufficiency decision, deliberately decoupled from candidate generation
     * (FASE 28). {@code HybridRetrievalService} now retrieves lexically with Spanish
     * OR-semantics, which is what finally made the lexical branch contribute candidates at all
     * (~3.6% of retrievals before), but it also means a single shared word is enough to produce
     * a candidate. Candidate generation optimizing for recall and grounding optimizing for
     * precision are different jobs, and conflating them is what made the earlier attempt at this
     * fix regress: under the previous {@code lexicalScore >= minRank} rule, an out-of-corpus
     * question measured {@code ts_rank_cd} 2.0 against an unrelated chunk - 40x the configured
     * {@code minRank} - and would have been answered as DOCUMENT_GROUNDED.
     *
     * <p>So the lexical branch may only ground an answer when the candidate clears {@code
     * minRank} <em>and</em> covers at least {@code minTermCoverage} of the question's lexemes.
     * Coverage is the query-length-normalized signal; rank is not, and rank alone is therefore
     * never sufficient. The semantic criterion is unchanged (FASE 5's original) and remains the
     * primary grounding signal when real embeddings are in use.
     */
    private boolean hasQualifyingCandidate(List<HybridRetrievalResult> candidates) {
        return candidates.stream().anyMatch(this::isSufficientEvidence);
    }

    private boolean isSufficientEvidence(HybridRetrievalResult candidate) {
        boolean semanticallyGrounded = candidate.semanticScore() != null
                && candidate.semanticScore() >= properties.rag().semantic().similarityThreshold();
        boolean lexicallyGrounded = candidate.lexicalScore() != null
                && candidate.lexicalScore() >= properties.rag().lexical().minRank()
                && candidate.lexicalTermCoverage() != null
                && candidate.lexicalTermCoverage() >= properties.rag().lexical().minTermCoverage();
        return semanticallyGrounded || lexicallyGrounded;
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

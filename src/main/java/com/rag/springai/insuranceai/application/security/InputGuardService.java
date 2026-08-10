package com.rag.springai.insuranceai.application.security;

import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import com.rag.springai.insuranceai.domain.security.PiiAssessment;
import com.rag.springai.insuranceai.domain.security.PromptInjectionAssessment;
import com.rag.springai.insuranceai.ports.outbound.PiiGuardPort;
import com.rag.springai.insuranceai.ports.outbound.PromptInjectionGuardPort;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Runs the input-side guardrails (brief FASE 8 section 9, RAG pipeline step "Prompt Injection
 * Guard" / "PII handling" from section 15) on an incoming question, before any retrieval
 * happens.
 *
 * <p><b>Why prompt injection blocks the request but PII does not:</b> a question that itself
 * tries to override system instructions ("ignore previous instructions...") has no legitimate
 * use in an internal insurance knowledge assistant - blocking it outright is safe and correct.
 * A question that happens to contain PII (e.g. "what does my policy ES91... cover?") can be a
 * completely legitimate internal query; this PoC's threat model (brief section 12: the system
 * never makes decisions about people) does not require blocking such questions, only ensuring
 * PII is not carelessly persisted/logged in plain text (data minimization) - see
 * {@link #sanitizeForLogging(String)}.
 *
 * <p>Both guards are individually toggleable ({@code insurance-ai.security.prompt-injection
 * .enabled} / {@code insurance-ai.security.pii.enabled}) - disabled means "skip the scan
 * entirely", not "scan but ignore the result", so the configuration's effect is directly
 * observable end to end.
 */
@Service
public class InputGuardService {

    private final PromptInjectionGuardPort promptInjectionGuardPort;
    private final PiiGuardPort piiGuardPort;
    private final InsuranceAiProperties properties;

    public InputGuardService(PromptInjectionGuardPort promptInjectionGuardPort, PiiGuardPort piiGuardPort,
            InsuranceAiProperties properties) {
        this.promptInjectionGuardPort = Objects.requireNonNull(promptInjectionGuardPort,
                "promptInjectionGuardPort must not be null");
        this.piiGuardPort = Objects.requireNonNull(piiGuardPort, "piiGuardPort must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public InputGuardAssessment assessQuestion(String question) {
        return new InputGuardAssessment(scanForInjection(question), scanForPii(question));
    }

    /**
     * Reused for both the question and, separately, each retrieved chunk's content (brief FASE 8
     * section 23: retrieved documents are untrusted data - detecting an indirect injection
     * attempt inside one is observability/audit-readiness, not blocking, since the structural
     * system/user boundary already neutralizes it - see {@code RuleBasedPromptInjectionGuard}).
     */
    public PromptInjectionAssessment scanForInjection(String text) {
        return properties.security().promptInjection().enabled() ? promptInjectionGuardPort.scan(text)
                : PromptInjectionAssessment.clean();
    }

    /** Reused for both the question and the LLM's generated answer (output PII observability). */
    public PiiAssessment scanForPii(String text) {
        return properties.security().pii().enabled() ? piiGuardPort.scan(text) : PiiAssessment.clean();
    }

    /** Redacts PII from {@code text} for safe logging, when PII detection is enabled. */
    public String sanitizeForLogging(String text) {
        return properties.security().pii().enabled() ? piiGuardPort.redact(text) : text;
    }
}

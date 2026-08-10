package com.rag.springai.insuranceai.adapters.shared.llm;

import org.springframework.http.HttpStatusCode;

/**
 * Classifies a 4xx {@code HttpClientErrorException} from an LLM provider as retry-safe or not
 * (FASE 14 audit remediation - see {@code docs/adr/ADR-012-AUDIT-REMEDIATION.md}). Shared by
 * {@code OpenAiLlmAdapter}/{@code AnthropicLlmAdapter} so the two adapters cannot drift on this
 * classification.
 *
 * <p>Not every 4xx is permanent: {@code 429 Too Many Requests} (rate limit) and {@code 408
 * Request Timeout} are conventionally transient - a caller-level retry with backoff can plausibly
 * still succeed, unlike {@code 400}/{@code 401}/{@code 403}/{@code 404}, which reflect a request
 * or credential problem that will not resolve itself. The original FASE 11 version of this
 * classification treated every {@code HttpClientErrorException} as permanent; that was too
 * coarse.
 */
public final class LlmFailureClassifier {

    private LlmFailureClassifier() {
    }

    public static boolean isTransientHttpStatus(HttpStatusCode status) {
        int code = status.value();
        return code == 429 || code == 408;
    }
}

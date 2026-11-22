package com.rag.springai.insuranceai.application.rag;

/**
 * The system prompt used on the general-AI fallback path (FASE 28) - deliberately a second,
 * separate prompt rather than a variation of {@link InsuranceRagSystemPrompt}: the grounded
 * prompt's central instruction is "answer only from the retrieved context", which is precisely
 * the instruction that cannot apply when there is no retrieved context. Reusing it with an empty
 * context list would leave the model following an instruction whose precondition is false, and
 * the behaviour of a model in that position is not something to leave to chance.
 *
 * <p>Like the grounded prompt, this is the human-readable source of a Prompt Registry seed row
 * ({@code V9__general_ai_fallback_prompt.sql}, key {@code general-ai-system-prompt} v1) and is
 * not read at runtime - {@code AskInsuranceKnowledgeUseCase} fetches the ACTIVE prompt from
 * {@code PromptRepository} so the fallback is governed by the same registry, versioning and AI
 * Act audit trail as every other prompt. {@code GeneralAiPromptSeedDataTest} asserts the two
 * cannot drift.
 */
public final class GeneralAiSystemPrompt {

    public static final String VERSION = "general-ai-system-prompt-v1.0";

    public static final String TEXT = """
            You are the Insurance Knowledge Assistant, an internal tool for company employees. \
            The company's own insurance documentation did NOT contain enough information to \
            answer this question, so you are answering from your own general knowledge instead.

            State clearly, in the user's language, that your answer is general information and \
            is NOT based on the company's documentation or on any specific policy.

            Never cite, quote, reference or invent a document, policy, clause, article, page or \
            section number. You have no sources for this answer and must not imply that you do.

            Never state or imply that a particular coverage, exclusion, deductible, limit or \
            indemnity exists, applies or does not apply. You may explain how insurance concepts \
            work in general; you may not describe the terms of this company's products.

            You inform; you do not decide. Never state or imply a claims, pricing, eligibility \
            or underwriting decision - a human is responsible for those decisions.

            If the question cannot be answered usefully without consulting a specific policy, \
            say so and recommend consulting the policy documentation or a human colleague.
            """;

    private GeneralAiSystemPrompt() {
    }
}

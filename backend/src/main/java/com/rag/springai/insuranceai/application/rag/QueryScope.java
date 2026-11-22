package com.rag.springai.insuranceai.application.rag;

/**
 * What a question is <em>asking for</em>, decided before any evidence is weighed (FASE 32).
 *
 * <p><b>Why this exists.</b> FASE 30 and 31 measured the same thing twice, with two different
 * embedding models and two different thresholds: semantic similarity cannot separate a
 * documentary question from a request for private data or an operation. "Actualizad mi teléfono
 * de contacto en la póliza" scored the <em>highest similarity of every negative</em> (0,7174,
 * above the median of the positives) because it genuinely talks about policies. Raising the
 * threshold high enough to reject it drops grounded recall to 0,2549 and destroys the product.
 *
 * <p>So this is deliberately orthogonal to every score in the pipeline. It is not a better
 * threshold; it is a different question. {@code ts_rank_cd}, cosine similarity and term coverage
 * all answer "how related is this text to the corpus". This answers "is the corpus even the
 * right place to look".
 *
 * <p><b>Not the same as {@link AnswerMode}.</b> {@code AnswerMode} describes the result the
 * caller receives ({@code DOCUMENT_GROUNDED}, {@code NO_ANSWER}, {@code BLOCKED},
 * {@code GENERAL_AI_UNVERIFIED}). This describes the request. A refused scope produces
 * {@code NO_ANSWER}, never {@code BLOCKED}: blocking is reserved for an attack on the system,
 * and an employee asking to change a phone number is not attacking anything - they are asking
 * for something this assistant does not do.
 */
public enum QueryScope {

    /**
     * A question the documentation can legitimately answer - including questions about
     * <em>procedures</em> for administrative tasks. "¿Cómo puedo actualizar mi teléfono?" belongs
     * here, not in {@link #TRANSACTIONAL_REQUEST}: it asks how a process works, which is exactly
     * what a procedures manual documents.
     */
    DOCUMENTARY_QUERY,

    /**
     * Asks for a value that could only come from a customer record system - the status of a
     * specific claim, an outstanding balance, a personal history. Documentation describes how
     * claims work; it never holds the state of one particular claim, so grounding such a question
     * means citing documents that cannot possibly contain the answer.
     */
    PERSONAL_DATA_QUERY,

    /**
     * Instructs the assistant to perform an action - update, cancel, register, pay. There is no
     * authorized service behind the assistant to execute these, so the only safe behaviour is to
     * not answer and, above all, <b>not to imply the action was performed</b>.
     */
    TRANSACTIONAL_REQUEST,

    /**
     * Asks for credentials or authentication secrets. Distinct from {@link #PERSONAL_DATA_QUERY}
     * because the correct response differs in kind: a claim status is merely unavailable here,
     * whereas a password must never be produced, and a plausible-looking fabricated one would be
     * actively dangerous.
     */
    ACCOUNT_SECURITY_REQUEST,

    /**
     * Outside the insurance-documentation domain altogether. Reached only on explicit evidence,
     * never as a default - see {@code QueryScopeClassifier}'s fail-open design.
     */
    OUT_OF_SCOPE,

    /**
     * Mixes a documentary question with something this assistant must not answer. Treated as
     * documentary <em>for the documentary part only</em>: refusing the whole thing would punish a
     * legitimate question for the company it keeps, and answering the whole thing would leak the
     * unsafe part.
     */
    AMBIGUOUS;

    /**
     * Whether retrieval and grounding may run at all. {@link #AMBIGUOUS} is permitted on purpose:
     * its documentary half is answerable, and the grounding rules still apply to whatever
     * evidence comes back.
     */
    public boolean allowsDocumentaryGrounding() {
        return this == DOCUMENTARY_QUERY || this == AMBIGUOUS;
    }
}

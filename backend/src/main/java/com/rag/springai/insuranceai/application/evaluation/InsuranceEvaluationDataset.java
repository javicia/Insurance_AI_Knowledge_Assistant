package com.rag.springai.insuranceai.application.evaluation;

import com.rag.springai.insuranceai.domain.evaluation.EvaluationCase;
import com.rag.springai.insuranceai.domain.evaluation.ExpectedOutcome;

import java.util.List;

/**
 * The PoC's one built-in, reproducible evaluation dataset (brief FASE 10 section 10: "a small
 * reproducible dataset"). Fixed in code - like {@code WellKnownAiSystems}/{@code
 * InsuranceRagSystemPrompt}'s historical seed content - rather than loaded from an external file,
 * since a PoC-scale dataset of 7 cases does not justify a file format/parser.
 *
 * <p>Four in-scope cases (coverage, exclusions, claims procedure, waiting period), each expecting
 * a {@link ExpectedOutcome#GROUNDED} answer citing one specific document, plus three
 * deliberately out-of-scope cases (executive compensation, a specific customer's account details,
 * stock price) expecting {@link ExpectedOutcome#NO_ANSWER} (brief FASE 10 section 10's own
 * examples). Pairs with the four short documents {@code EvaluationIntegrationTest} ingests before
 * running this dataset - the expected document names below must match those ingested there
 * exactly, or every {@code GROUNDED} case's {@code sourceHit} will read false.
 */
public final class InsuranceEvaluationDataset {

    public static final String NAME = "insurance-knowledge-baseline-v1";

    public static final List<EvaluationCase> CASES = List.of(
            new EvaluationCase("coverage-water-damage", "Is water damage from a burst pipe covered?", "coverage",
                    ExpectedOutcome.GROUNDED, "Home Insurance Policy"),
            new EvaluationCase("exclusions-flood-damage",
                    "Is flood damage from a natural disaster excluded from coverage?", "exclusions",
                    ExpectedOutcome.GROUNDED, "Policy Exclusions"),
            new EvaluationCase("claims-procedure-deadline", "Must claims be submitted within 30 days of the incident?",
                    "claims-procedure", ExpectedOutcome.GROUNDED, "Claims Procedure"),
            new EvaluationCase("waiting-period-travel-medical",
                    "Does travel medical coverage begin only after a waiting period of 14 days?", "waiting-period",
                    ExpectedOutcome.GROUNDED, "Travel Insurance Policy"),
            new EvaluationCase("out-of-scope-ceo-salary", "What is the CEO's salary?", "out-of-scope",
                    ExpectedOutcome.NO_ANSWER, null),
            new EvaluationCase("out-of-scope-customer-bank-account",
                    "What is a specific customer's bank account number?", "out-of-scope", ExpectedOutcome.NO_ANSWER,
                    null),
            new EvaluationCase("out-of-scope-stock-price", "What is the company's current stock price?",
                    "out-of-scope", ExpectedOutcome.NO_ANSWER, null));

    private InsuranceEvaluationDataset() {
    }
}

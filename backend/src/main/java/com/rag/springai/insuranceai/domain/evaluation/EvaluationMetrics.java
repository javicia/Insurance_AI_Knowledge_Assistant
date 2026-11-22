package com.rag.springai.insuranceai.domain.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Aggregate metrics computed from a list of {@link EvaluationCaseResult} (brief FASE 10 section
 * 10). A pure function of its inputs ({@link #compute}) - no persistence, no configuration, no
 * dependency on the use case that produced the results.
 *
 * <p><b>What is deliberately not here:</b> Precision@K is not computed. This PoC's dataset (see
 * {@code InsuranceEvaluationDataset}) labels exactly one relevant document per in-scope question,
 * with no relevance judgement recorded for the other chunks a case's retrieval might have
 * surfaced - so a precision figure would either always read 1/{@code finalTopK} (uninformative)
 * or require inventing ground truth that does not exist. Per brief FASE 10 section 10's own
 * instruction, this is stated plainly rather than fabricated.
 *
 * <p>Every ratio below is <b>vacuously 1.0</b> (or, for {@link #mrr}, vacuously 0.0) when its
 * denominator subset is empty - e.g. {@code recallAtK} is 1.0 if the dataset contains zero
 * {@code GROUNDED}-expected cases. This mirrors how JUnit reports zero assertions as passing: an
 * empty check proves nothing was contradicted, not that something was proven. Callers should
 * treat a vacuous 1.0 as "not evaluated" rather than "perfect", most concretely by keeping {@code
 * totalCases} in view alongside every ratio.
 */
public record EvaluationMetrics(int totalCases, double outcomeAccuracy, double groundingRate,
        double noAnswerAccuracy, double recallAtK, double mrr, double citationCoverage) {

    public EvaluationMetrics {
        if (totalCases < 0) {
            throw new IllegalArgumentException("totalCases must not be negative");
        }
    }

    public static EvaluationMetrics compute(List<EvaluationCaseResult> results) {
        Objects.requireNonNull(results, "results must not be null");

        List<EvaluationCaseResult> groundedExpected = results.stream()
                .filter(r -> r.expectedOutcome() == ExpectedOutcome.GROUNDED)
                .toList();
        List<EvaluationCaseResult> noAnswerExpected = results.stream()
                .filter(r -> r.expectedOutcome() == ExpectedOutcome.NO_ANSWER)
                .toList();
        List<EvaluationCaseResult> actuallyGrounded = results.stream()
                .filter(r -> r.actualOutcome() == ExpectedOutcome.GROUNDED)
                .toList();

        double outcomeAccuracy = ratio(results, EvaluationCaseResult::outcomeMatch);
        double groundingRate = ratio(groundedExpected, EvaluationCaseResult::outcomeMatch);
        double noAnswerAccuracy = ratio(noAnswerExpected, EvaluationCaseResult::outcomeMatch);
        double recallAtK = ratio(groundedExpected, r -> Boolean.TRUE.equals(r.sourceHit()));
        double mrr = groundedExpected.isEmpty() ? 0.0
                : groundedExpected.stream().mapToDouble(r -> r.reciprocalRank() == null ? 0.0 : r.reciprocalRank())
                        .average()
                        .orElse(0.0);
        double citationCoverage = ratio(actuallyGrounded, r -> r.citationCount() >= 1);

        return new EvaluationMetrics(results.size(), outcomeAccuracy, groundingRate, noAnswerAccuracy, recallAtK,
                mrr, citationCoverage);
    }

    private static double ratio(List<EvaluationCaseResult> subset, java.util.function.Predicate<EvaluationCaseResult> matches) {
        if (subset.isEmpty()) {
            return 1.0;
        }
        return subset.stream().filter(matches).count() / (double) subset.size();
    }
}

package com.rag.springai.insuranceai.domain.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluationMetricsTest {

    @Test
    void computeReturnsVacuousOnesAndZeroMrrForAnEmptyResultList() {
        EvaluationMetrics metrics = EvaluationMetrics.compute(List.of());

        assertEquals(0, metrics.totalCases());
        assertEquals(1.0, metrics.outcomeAccuracy());
        assertEquals(1.0, metrics.groundingRate());
        assertEquals(1.0, metrics.noAnswerAccuracy());
        assertEquals(1.0, metrics.recallAtK());
        assertEquals(0.0, metrics.mrr());
        assertEquals(1.0, metrics.citationCoverage());
    }

    @Test
    void computeMixesCorrectAndIncorrectCasesAcrossBothExpectedOutcomes() {
        List<EvaluationCaseResult> results = List.of(
                // Grounded-expected, correctly grounded, expected source found first -> RR 1.0
                new EvaluationCaseResult("c1", "coverage", ExpectedOutcome.GROUNDED, ExpectedOutcome.GROUNDED, true,
                        true, 1.0, 2, "trace-1"),
                // Grounded-expected, correctly grounded, but expected source found second -> RR 0.5
                new EvaluationCaseResult("c2", "coverage", ExpectedOutcome.GROUNDED, ExpectedOutcome.GROUNDED, true,
                        true, 0.5, 1, "trace-2"),
                // Grounded-expected, but actually produced no answer at all -> outcome mismatch, no source hit
                new EvaluationCaseResult("c3", "exclusions", ExpectedOutcome.GROUNDED, ExpectedOutcome.NO_ANSWER,
                        false, false, 0.0, 0, "trace-3"),
                // No-answer-expected, correctly produced no answer
                new EvaluationCaseResult("c4", "out-of-scope", ExpectedOutcome.NO_ANSWER, ExpectedOutcome.NO_ANSWER,
                        true, null, null, 0, "trace-4"),
                // No-answer-expected, but the guardrail-free pipeline grounded it anyway (false positive)
                new EvaluationCaseResult("c5", "out-of-scope", ExpectedOutcome.NO_ANSWER, ExpectedOutcome.GROUNDED,
                        false, null, null, 1, "trace-5"));

        EvaluationMetrics metrics = EvaluationMetrics.compute(results);

        assertEquals(5, metrics.totalCases());
        assertEquals(3.0 / 5.0, metrics.outcomeAccuracy());
        assertEquals(2.0 / 3.0, metrics.groundingRate());
        assertEquals(1.0 / 2.0, metrics.noAnswerAccuracy());
        assertEquals(2.0 / 3.0, metrics.recallAtK());
        assertEquals((1.0 + 0.5 + 0.0) / 3.0, metrics.mrr());
        // 3 cases actually GROUNDED (c1, c2, c5), all with citationCount >= 1
        assertEquals(3.0 / 3.0, metrics.citationCoverage());
    }
}

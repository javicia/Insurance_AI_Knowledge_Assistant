package com.rag.springai.insuranceai.application.evaluation;

import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeCommand;
import com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeUseCase;
import com.rag.springai.insuranceai.application.rag.Grounding;
import com.rag.springai.insuranceai.application.rag.GroundingStatus;
import com.rag.springai.insuranceai.application.rag.RagAnswer;
import com.rag.springai.insuranceai.application.rag.SourceReference;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationCase;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRun;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRunStatus;
import com.rag.springai.insuranceai.domain.evaluation.ExpectedOutcome;
import com.rag.springai.insuranceai.ports.outbound.EvaluationRunRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationRunnerServiceTest {

    private final AskInsuranceKnowledgeUseCase askInsuranceKnowledgeUseCase = mock(AskInsuranceKnowledgeUseCase.class);
    private final EvaluationRunRepository evaluationRunRepository = mock(EvaluationRunRepository.class);

    @Test
    void runComputesMetricsAndPersistsAPassedRunWhenEveryCaseMatchesExpectations() {
        EvaluationRunnerService service = new EvaluationRunnerService(askInsuranceKnowledgeUseCase,
                evaluationRunRepository, properties(1.0, 1.0, 1.0));

        EvaluationCase groundedCase = new EvaluationCase("case-1", "Is water damage covered?", "coverage",
                ExpectedOutcome.GROUNDED, "Home Insurance Policy");
        EvaluationCase noAnswerCase = new EvaluationCase("case-2", "What is the CEO's salary?", "out-of-scope",
                ExpectedOutcome.NO_ANSWER, null);

        when(askInsuranceKnowledgeUseCase.ask(argThatQuestionEquals("Is water damage covered?")))
                .thenReturn(new RagAnswer("Yes, covered.",
                        List.of(new SourceReference("doc-1", "Home Insurance Policy", "1.0", 1, "Water Damage",
                                "chunk-1")),
                        new Grounding(GroundingStatus.GROUNDED), "trace-1", false));
        when(askInsuranceKnowledgeUseCase.ask(argThatQuestionEquals("What is the CEO's salary?")))
                .thenReturn(RagAnswer.noAnswer("trace-2"));

        EvaluationRun run = service.run("test-dataset", List.of(groundedCase, noAnswerCase));

        assertEquals("test-dataset", run.datasetName());
        assertEquals(2, run.results().size());
        assertEquals(EvaluationRunStatus.PASSED, run.status());
        assertEquals(1.0, run.metrics().groundingRate());
        assertEquals(1.0, run.metrics().noAnswerAccuracy());
        assertEquals(1.0, run.metrics().recallAtK());
        assertEquals(1.0, run.metrics().mrr());
        verify(evaluationRunRepository).save(run);
    }

    @Test
    void runMarksTheRunFailedWhenAMetricFallsBelowItsConfiguredThreshold() {
        EvaluationRunnerService service = new EvaluationRunnerService(askInsuranceKnowledgeUseCase,
                evaluationRunRepository, properties(1.0, 1.0, 1.0));

        EvaluationCase groundedCase = new EvaluationCase("case-1", "Is water damage covered?", "coverage",
                ExpectedOutcome.GROUNDED, "Home Insurance Policy");

        // The pipeline fails to find any relevant evidence at all - a real regression.
        when(askInsuranceKnowledgeUseCase.ask(any())).thenReturn(RagAnswer.noAnswer("trace-1"));

        EvaluationRun run = service.run("test-dataset", List.of(groundedCase));

        assertEquals(EvaluationRunStatus.FAILED, run.status());
        assertTrue(run.results().get(0).sourceHit() == Boolean.FALSE);
    }

    private static AskInsuranceKnowledgeCommand argThatQuestionEquals(String question) {
        return org.mockito.ArgumentMatchers.argThat(command -> command != null && command.question().equals(question));
    }

    private static InsuranceAiProperties properties(double minGroundingRate, double minNoAnswerAccuracy,
            double minRecallAtK) {
        InsuranceAiProperties.Rag rag = new InsuranceAiProperties.Rag(new InsuranceAiProperties.Rag.Semantic(8, 0.75),
                new InsuranceAiProperties.Rag.Lexical(8, 0.0), new InsuranceAiProperties.Rag.Hybrid(20, 8, 60.0),
                new InsuranceAiProperties.Rag.Reranking(true), new InsuranceAiProperties.Rag.QueryExpansion(false, 3),
                new InsuranceAiProperties.Rag.Context(6000));
        return new InsuranceAiProperties(rag,
                new InsuranceAiProperties.Security(new InsuranceAiProperties.Security.PromptInjection(true),
                        new InsuranceAiProperties.Security.Pii(true)),
                new InsuranceAiProperties.Governance(new InsuranceAiProperties.Governance.Audit(true)),
                new InsuranceAiProperties.Ai(InsuranceAiProperties.SupportedAiProvider.FAKE),
                new InsuranceAiProperties.Evaluation(new InsuranceAiProperties.Evaluation.Thresholds(minGroundingRate,
                        minNoAnswerAccuracy, minRecallAtK)));
    }
}

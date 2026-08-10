package com.rag.springai.insuranceai.application.evaluation;

import com.rag.springai.insuranceai.application.configuration.InsuranceAiProperties;
import com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeCommand;
import com.rag.springai.insuranceai.application.rag.AskInsuranceKnowledgeUseCase;
import com.rag.springai.insuranceai.application.rag.GroundingStatus;
import com.rag.springai.insuranceai.application.rag.RagAnswer;
import com.rag.springai.insuranceai.application.rag.SourceReference;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationCase;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationCaseResult;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationMetrics;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRun;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRunId;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRunStatus;
import com.rag.springai.insuranceai.domain.evaluation.ExpectedOutcome;
import com.rag.springai.insuranceai.domain.shared.TraceId;
import com.rag.springai.insuranceai.ports.outbound.EvaluationRunRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs an {@link EvaluationCase} list through the real {@link AskInsuranceKnowledgeUseCase} - the
 * same use case {@code POST /api/chat} calls, not a shortcut around retrieval/grounding/LLM - and
 * persists the resulting {@link EvaluationRun} (brief FASE 10 section 10).
 *
 * <p>Each case's {@code actualOutcome} collapses {@link RagAnswer}'s {@code GroundingStatus} to
 * {@link ExpectedOutcome}: {@code GROUNDED -> GROUNDED}, {@code NOT_GROUNDED -> NO_ANSWER}. This
 * is a deliberate simplification - {@code NOT_GROUNDED} is also what a blocked (prompt-injection)
 * request produces (see {@code RagAnswer#blocked}), but {@code InsuranceEvaluationDataset}
 * contains no injection cases (that resistance is already exercised by FASE 8's {@code
 * SecurityGuardrailIntegrationTest}), so the collapse never actually conflates the two here.
 */
@Service
public class EvaluationRunnerService {

    private final AskInsuranceKnowledgeUseCase askInsuranceKnowledgeUseCase;
    private final EvaluationRunRepository evaluationRunRepository;
    private final InsuranceAiProperties properties;

    public EvaluationRunnerService(AskInsuranceKnowledgeUseCase askInsuranceKnowledgeUseCase,
            EvaluationRunRepository evaluationRunRepository, InsuranceAiProperties properties) {
        this.askInsuranceKnowledgeUseCase = Objects.requireNonNull(askInsuranceKnowledgeUseCase,
                "askInsuranceKnowledgeUseCase must not be null");
        this.evaluationRunRepository = Objects.requireNonNull(evaluationRunRepository,
                "evaluationRunRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public EvaluationRun runBuiltInDataset() {
        return run(InsuranceEvaluationDataset.NAME, InsuranceEvaluationDataset.CASES);
    }

    public EvaluationRun run(String datasetName, List<EvaluationCase> cases) {
        Objects.requireNonNull(datasetName, "datasetName must not be null");
        Objects.requireNonNull(cases, "cases must not be null");

        Instant startedAt = Instant.now();
        List<EvaluationCaseResult> results = new ArrayList<>();
        for (EvaluationCase evaluationCase : cases) {
            results.add(runCase(evaluationCase));
        }
        EvaluationMetrics metrics = EvaluationMetrics.compute(results);
        EvaluationRunStatus status = meetsThresholds(metrics) ? EvaluationRunStatus.PASSED
                : EvaluationRunStatus.FAILED;

        EvaluationRun run = new EvaluationRun(EvaluationRunId.generate(), datasetName, startedAt, Instant.now(),
                results, metrics, status);
        evaluationRunRepository.save(run);
        return run;
    }

    public Optional<EvaluationRun> findById(EvaluationRunId id) {
        return evaluationRunRepository.findById(id);
    }

    public List<EvaluationRun> findRecent(int limit) {
        return evaluationRunRepository.findRecent(limit);
    }

    private EvaluationCaseResult runCase(EvaluationCase evaluationCase) {
        TraceId traceId = TraceId.generate();
        RagAnswer answer = askInsuranceKnowledgeUseCase
                .ask(new AskInsuranceKnowledgeCommand(evaluationCase.question(), traceId));

        ExpectedOutcome actualOutcome = answer.grounding().status() == GroundingStatus.GROUNDED
                ? ExpectedOutcome.GROUNDED
                : ExpectedOutcome.NO_ANSWER;
        boolean outcomeMatch = actualOutcome == evaluationCase.expectedOutcome();

        Boolean sourceHit = null;
        Double reciprocalRank = null;
        if (evaluationCase.expectedOutcome() == ExpectedOutcome.GROUNDED) {
            int position = indexOfExpectedSource(answer.sources(), evaluationCase.expectedSourceDocument());
            sourceHit = position >= 0;
            reciprocalRank = position >= 0 ? 1.0 / (position + 1) : 0.0;
        }

        return new EvaluationCaseResult(evaluationCase.caseId(), evaluationCase.category(),
                evaluationCase.expectedOutcome(), actualOutcome, outcomeMatch, sourceHit, reciprocalRank,
                answer.sources().size(), traceId.value());
    }

    private int indexOfExpectedSource(List<SourceReference> sources, String expectedDocument) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).document().equals(expectedDocument)) {
                return i;
            }
        }
        return -1;
    }

    private boolean meetsThresholds(EvaluationMetrics metrics) {
        InsuranceAiProperties.Evaluation.Thresholds thresholds = properties.evaluation().thresholds();
        return metrics.groundingRate() >= thresholds.minGroundingRate()
                && metrics.noAnswerAccuracy() >= thresholds.minNoAnswerAccuracy()
                && metrics.recallAtK() >= thresholds.minRecallAtK();
    }
}

package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.evaluation.EvaluationRun;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRunId;

import java.util.List;
import java.util.Optional;

public interface EvaluationRunRepository {

    void save(EvaluationRun run);

    Optional<EvaluationRun> findById(EvaluationRunId id);

    List<EvaluationRun> findRecent(int limit);
}

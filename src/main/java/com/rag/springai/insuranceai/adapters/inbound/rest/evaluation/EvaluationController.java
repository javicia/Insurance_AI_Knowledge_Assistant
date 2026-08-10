package com.rag.springai.insuranceai.adapters.inbound.rest.evaluation;

import com.rag.springai.insuranceai.application.evaluation.EvaluationRunNotFoundException;
import com.rag.springai.insuranceai.application.evaluation.EvaluationRunnerService;
import com.rag.springai.insuranceai.domain.evaluation.EvaluationRunId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI Evaluation REST API (brief FASE 10 section 10). {@code POST /runs} runs {@code
 * InsuranceEvaluationDataset}'s built-in dataset through the real RAG pipeline synchronously and
 * returns the completed run - deliberately not async/queued (brief section 61: no overengineering
 * for a dataset of 7 cases against a local Postgres/fake-or-real LLM).
 */
@RestController
@RequestMapping("/api/evaluation")
@Tag(name = "AI Evaluation", description = "Runs the built-in regression dataset through the real RAG pipeline "
        + "and reports Recall@K/MRR/grounding-rate/no-answer-accuracy/citation-coverage metrics.")
class EvaluationController {

    private final EvaluationRunnerService evaluationRunnerService;

    EvaluationController(EvaluationRunnerService evaluationRunnerService) {
        this.evaluationRunnerService = evaluationRunnerService;
    }

    @PostMapping("/runs")
    @Operation(summary = "Run the built-in evaluation dataset",
            description = "Synchronous - requires the dataset's matching documents (see "
                    + "docs/evaluation/AI_EVALUATION.md) to already be ingested for a meaningful (non-zero "
                    + "recall) result.")
    ResponseEntity<EvaluationRunResponse> runBuiltInDataset() {
        var run = evaluationRunnerService.runBuiltInDataset();
        return ResponseEntity.status(HttpStatus.CREATED).body(EvaluationRunResponse.from(run));
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Get one evaluation run, including every per-case result")
    EvaluationRunResponse getRun(@PathVariable String id) {
        return evaluationRunnerService.findById(EvaluationRunId.of(id))
                .map(EvaluationRunResponse::from)
                .orElseThrow(() -> new EvaluationRunNotFoundException(EvaluationRunId.of(id)));
    }

    @GetMapping("/runs/recent")
    @Operation(summary = "List the most recent evaluation runs, newest first")
    List<EvaluationRunResponse> recent(@RequestParam(defaultValue = "20") int limit) {
        return evaluationRunnerService.findRecent(limit).stream().map(EvaluationRunResponse::from).toList();
    }
}

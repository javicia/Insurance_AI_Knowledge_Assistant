/**
 * Use cases for running RAG evaluation datasets and reporting metrics (FASE 10). {@link
 * com.rag.springai.insuranceai.application.evaluation.EvaluationRunnerService} runs {@link
 * com.rag.springai.insuranceai.application.evaluation.InsuranceEvaluationDataset}'s built-in
 * dataset (or any other {@code List<EvaluationCase>}) through the real {@code
 * AskInsuranceKnowledgeUseCase} and persists an {@code EvaluationRun}. See {@code
 * docs/evaluation/AI_EVALUATION.md}.
 */
package com.rag.springai.insuranceai.application.evaluation;

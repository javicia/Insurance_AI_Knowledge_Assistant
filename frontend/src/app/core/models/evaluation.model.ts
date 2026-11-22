/**
 * Mirrors adapters.inbound.rest.evaluation.{EvaluationRunResponse,EvaluationMetricsResponse,
 * EvaluationCaseResultResponse} exactly.
 */

export type EvaluationRunStatus = 'PASSED' | 'FAILED';
export type ExpectedOutcome = 'GROUNDED' | 'NO_ANSWER';

export interface EvaluationMetricsResponse {
  readonly totalCases: number;
  readonly outcomeAccuracy: number;
  readonly groundingRate: number;
  readonly noAnswerAccuracy: number;
  readonly recallAtK: number;
  readonly mrr: number;
  readonly citationCoverage: number;
}

export interface EvaluationCaseResultResponse {
  readonly caseId: string;
  readonly category: string;
  readonly expectedOutcome: ExpectedOutcome;
  readonly actualOutcome: ExpectedOutcome;
  readonly outcomeMatch: boolean;
  readonly sourceHit: boolean | null;
  readonly reciprocalRank: number | null;
  readonly citationCount: number;
  readonly traceId: string;
}

export interface EvaluationRunResponse {
  readonly id: string;
  readonly datasetName: string;
  readonly startedAt: string;
  readonly completedAt: string;
  readonly status: EvaluationRunStatus;
  readonly metrics: EvaluationMetricsResponse;
  readonly results: readonly EvaluationCaseResultResponse[];
}

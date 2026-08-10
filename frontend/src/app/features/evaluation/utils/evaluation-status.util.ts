import type { StatusBadgeVariant } from '../../../shared/components/status-badge/status-badge';
import type { EvaluationRunStatus } from '../../../core/models/evaluation.model';

export function evaluationRunVariant(status: EvaluationRunStatus): StatusBadgeVariant {
  return status === 'PASSED' ? 'success' : 'danger';
}

export function formatPercent(ratio: number): string {
  return `${Math.round(ratio * 100)}%`;
}

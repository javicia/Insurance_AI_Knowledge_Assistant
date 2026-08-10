import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { formatPercent } from '../../utils/evaluation-status.util';
import type { EvaluationMetricsResponse } from '../../../../core/models/evaluation.model';

interface MetricTile {
  readonly label: string;
  readonly value: string;
}

/** Small metric tiles (brief FASE 15 section 23) - no charting library, no invented metrics. */
@Component({
  selector: 'app-evaluation-metrics',
  templateUrl: './evaluation-metrics.html',
  styleUrl: './evaluation-metrics.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EvaluationMetrics {
  readonly metrics = input.required<EvaluationMetricsResponse>();

  protected tiles(): readonly MetricTile[] {
    const metrics = this.metrics();
    return [
      { label: 'Grounding rate', value: formatPercent(metrics.groundingRate) },
      { label: 'No-answer accuracy', value: formatPercent(metrics.noAnswerAccuracy) },
      { label: 'Recall@K', value: formatPercent(metrics.recallAtK) },
      { label: 'MRR', value: metrics.mrr.toFixed(2) },
      { label: 'Citation coverage', value: formatPercent(metrics.citationCoverage) },
      { label: 'Outcome accuracy', value: formatPercent(metrics.outcomeAccuracy) },
    ];
  }
}

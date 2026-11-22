import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type StatusBadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

/**
 * A single, reusable semantic badge (brief FASE 15 section 18/26) - never relies on color alone,
 * always pairs it with a text label, used for document status, model/prompt/risk-assessment
 * status, and audit/evaluation outcomes.
 */
@Component({
  selector: 'app-status-badge',
  templateUrl: './status-badge.html',
  styleUrl: './status-badge.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusBadge {
  readonly label = input.required<string>();
  readonly variant = input<StatusBadgeVariant>('neutral');
}

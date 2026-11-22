import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { StatusBadge } from '../../../../shared/components/status-badge/status-badge';
import { auditOutcomeVariant } from '../../utils/audit-status.util';
import type { AuditRecordResponse } from '../../../../core/models/audit.model';

/**
 * Full detail for one audit record (brief FASE 15 section 22) - every field shown is a real
 * `AuditRecordResponse` field; the backend never returns raw question/answer/chunk text (data
 * minimization, brief FASE 9), so none is shown here either.
 */
@Component({
  selector: 'app-audit-detail-panel',
  imports: [MatButtonModule, MatIconModule, StatusBadge],
  templateUrl: './audit-detail-panel.html',
  styleUrl: './audit-detail-panel.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuditDetailPanel {
  readonly record = input.required<AuditRecordResponse>();
  readonly closed = output<void>();

  protected readonly auditOutcomeVariant = auditOutcomeVariant;
}

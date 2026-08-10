import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatTooltipModule } from '@angular/material/tooltip';
import { catchError, of } from 'rxjs';

import { AuditService } from '../../../../core/services/audit.service';
import { EmptyState } from '../../../../shared/components/empty-state/empty-state';
import { StatusBadge } from '../../../../shared/components/status-badge/status-badge';
import { AuditDetailPanel } from '../../components/audit-detail-panel/audit-detail-panel';
import { auditOutcomeVariant } from '../../utils/audit-status.util';
import type { AuditRecordResponse } from '../../../../core/models/audit.model';

@Component({
  selector: 'app-audit-page',
  imports: [MatIconModule, MatSidenavModule, MatTooltipModule, StatusBadge, EmptyState, AuditDetailPanel],
  templateUrl: './audit-page.html',
  styleUrl: './audit-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuditPage {
  private readonly auditService = inject(AuditService);

  protected readonly loading = signal(true);
  protected readonly records = signal<readonly AuditRecordResponse[]>([]);
  protected readonly selected = signal<AuditRecordResponse | null>(null);

  protected readonly auditOutcomeVariant = auditOutcomeVariant;

  constructor() {
    this.auditService
      .listRecent(50)
      .pipe(catchError(() => of([] as AuditRecordResponse[])))
      .subscribe((records) => {
        this.records.set(records);
        this.loading.set(false);
      });
  }

  protected select(record: AuditRecordResponse): void {
    this.selected.set(record);
  }

  protected trackById(_index: number, record: AuditRecordResponse): string {
    return record.id;
  }
}

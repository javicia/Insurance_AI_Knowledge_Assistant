import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { catchError, of } from 'rxjs';

import { EvaluationService } from '../../../../core/services/evaluation.service';
import { EmptyState } from '../../../../shared/components/empty-state/empty-state';
import { StatusBadge } from '../../../../shared/components/status-badge/status-badge';
import { EvaluationMetrics } from '../../components/evaluation-metrics/evaluation-metrics';
import { evaluationRunVariant } from '../../utils/evaluation-status.util';
import type { EvaluationRunResponse } from '../../../../core/models/evaluation.model';

@Component({
  selector: 'app-evaluation-page',
  imports: [MatButtonModule, MatIconModule, StatusBadge, EmptyState, EvaluationMetrics],
  templateUrl: './evaluation-page.html',
  styleUrl: './evaluation-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EvaluationPage {
  private readonly evaluationService = inject(EvaluationService);

  protected readonly loading = signal(true);
  protected readonly running = signal(false);
  protected readonly runs = signal<readonly EvaluationRunResponse[]>([]);

  protected readonly evaluationRunVariant = evaluationRunVariant;

  constructor() {
    this.loadRecent();
  }

  protected runBuiltInDataset(): void {
    this.running.set(true);
    this.evaluationService.runBuiltInDataset().subscribe({
      next: () => {
        this.running.set(false);
        this.loadRecent();
      },
      error: () => this.running.set(false),
    });
  }

  protected trackById(_index: number, run: EvaluationRunResponse): string {
    return run.id;
  }

  private loadRecent(): void {
    this.evaluationService
      .listRecent(10)
      .pipe(catchError(() => of([] as EvaluationRunResponse[])))
      .subscribe((runs) => {
        this.runs.set(runs);
        this.loading.set(false);
      });
  }
}

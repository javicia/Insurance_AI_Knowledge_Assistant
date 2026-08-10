import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatExpansionModule } from '@angular/material/expansion';
import { catchError, forkJoin, of, switchMap } from 'rxjs';

import { GovernanceService } from '../../../../core/services/governance.service';
import { EmptyState } from '../../../../shared/components/empty-state/empty-state';
import { StatusBadge } from '../../../../shared/components/status-badge/status-badge';
import { lifecycleVariant, riskVariant } from '../../utils/governance-status.util';
import type {
  AiSystemResponse,
  ModelResponse,
  PromptResponse,
  RiskAssessmentResponse,
} from '../../../../core/models/governance.model';

@Component({
  selector: 'app-governance-page',
  imports: [StatusBadge, EmptyState, MatExpansionModule],
  templateUrl: './governance-page.html',
  styleUrl: './governance-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GovernancePage {
  private readonly governanceService = inject(GovernanceService);

  protected readonly riskVariant = riskVariant;
  protected readonly lifecycleVariant = lifecycleVariant;

  protected readonly loading = signal(true);
  protected readonly aiSystem = signal<AiSystemResponse | null>(null);
  protected readonly models = signal<readonly ModelResponse[]>([]);
  protected readonly prompts = signal<readonly PromptResponse[]>([]);
  protected readonly riskAssessments = signal<readonly RiskAssessmentResponse[]>([]);

  constructor() {
    this.governanceService
      .listAiSystems()
      .pipe(
        switchMap((systems) => {
          const first = systems[0] ?? null;
          this.aiSystem.set(first);
          if (!first) {
            return of([[], [], []] as [ModelResponse[], PromptResponse[], RiskAssessmentResponse[]]);
          }
          return forkJoin([
            this.governanceService.listModels(first.id),
            this.governanceService.listPrompts(first.id),
            this.governanceService.listRiskAssessments(first.id),
          ]);
        }),
        catchError(() => of([[], [], []] as [ModelResponse[], PromptResponse[], RiskAssessmentResponse[]])),
      )
      .subscribe(([models, prompts, riskAssessments]) => {
        this.models.set(models);
        this.prompts.set(prompts);
        this.riskAssessments.set(riskAssessments);
        this.loading.set(false);
      });
  }

  protected trackById(_index: number, item: { id: string }): string {
    return item.id;
  }
}

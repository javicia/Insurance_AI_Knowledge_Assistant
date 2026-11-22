import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, of, switchMap, timer } from 'rxjs';

import { GovernanceService } from './governance.service';
import { HealthService } from './health.service';
import type { HealthState } from '../models/health.model';

const HEALTH_POLL_INTERVAL_MS = 30_000;

/**
 * Backs the header/sidebar "AI System status" indicator with the
 * real `GET /actuator/health` result, polled periodically - never a hardcoded "Operational"
 * claim. The active provider is read once from the real Model Registry
 * (`GET /api/governance/ai-systems/{id}/models`) rather than invented.
 */
@Injectable({ providedIn: 'root' })
export class SystemStatusService {
  private readonly healthService = inject(HealthService);
  private readonly governanceService = inject(GovernanceService);

  private readonly healthSignal = signal<HealthState>('UNKNOWN');
  readonly health = this.healthSignal.asReadonly();

  private readonly activeProviderSignal = signal<string | null>(null);
  readonly activeProvider = this.activeProviderSignal.asReadonly();

  readonly isOperational = computed(() => this.healthSignal() === 'UP');

  constructor() {
    timer(0, HEALTH_POLL_INTERVAL_MS)
      .pipe(switchMap(() => this.healthService.check()))
      .subscribe((state) => this.healthSignal.set(state));

    this.governanceService
      .listAiSystems()
      .pipe(
        switchMap((systems) => {
          const first = systems[0];
          return first ? this.governanceService.listModels(first.id) : of([]);
        }),
        catchError(() => of([])),
      )
      .subscribe((models) => {
        const active = models.find((model) => model.status === 'ACTIVE') ?? models[0];
        this.activeProviderSignal.set(active ? active.provider : null);
      });
  }
}

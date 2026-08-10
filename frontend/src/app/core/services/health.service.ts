import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';

import { ACTUATOR_BASE_URL } from '../config/api.config';
import type { HealthResponse, HealthState } from '../models/health.model';

/**
 * Consumes exactly `GET /actuator/health` (spring-boot-starter-actuator, FASE 11) - the header
 * status indicator (brief FASE 15 section 9) reflects this real check, never an assumed/hardcoded
 * "operational" claim (brief section 63).
 */
@Injectable({ providedIn: 'root' })
export class HealthService {
  private readonly http = inject(HttpClient);

  check(): Observable<HealthState> {
    return this.http.get<HealthResponse>(`${ACTUATOR_BASE_URL}/health`).pipe(
      map((response) => response.status ?? 'UNKNOWN'),
      catchError(() => of<HealthState>('DOWN')),
    );
  }
}

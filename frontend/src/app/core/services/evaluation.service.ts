import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../config/api.config';
import type { EvaluationRunResponse } from '../models/evaluation.model';

/** Consumes exactly the endpoints of EvaluationController (`/api/evaluation/**`). */
@Injectable({ providedIn: 'root' })
export class EvaluationService {
  private readonly http = inject(HttpClient);

  runBuiltInDataset(): Observable<EvaluationRunResponse> {
    return this.http.post<EvaluationRunResponse>(`${API_BASE_URL}/evaluation/runs`, null);
  }

  getById(id: string): Observable<EvaluationRunResponse> {
    return this.http.get<EvaluationRunResponse>(`${API_BASE_URL}/evaluation/runs/${encodeURIComponent(id)}`);
  }

  listRecent(limit = 20): Observable<EvaluationRunResponse[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<EvaluationRunResponse[]>(`${API_BASE_URL}/evaluation/runs/recent`, { params });
  }
}

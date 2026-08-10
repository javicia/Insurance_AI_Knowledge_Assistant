import { HttpClient } from '@angular/common/http';
import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../config/api.config';
import type { AuditRecordResponse } from '../models/audit.model';

/** Consumes exactly the read-only endpoints of AuditController (`/api/audit/**`). */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);

  getByTraceId(traceId: string): Observable<AuditRecordResponse> {
    return this.http.get<AuditRecordResponse>(`${API_BASE_URL}/audit/traces/${encodeURIComponent(traceId)}`);
  }

  listRecent(limit = 20): Observable<AuditRecordResponse[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<AuditRecordResponse[]>(`${API_BASE_URL}/audit/recent`, { params });
  }
}

import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../config/api.config';
import type {
  AiSystemResponse,
  ModelResponse,
  PromptResponse,
  RiskAssessmentResponse,
} from '../models/governance.model';

/**
 * Consumes the real read endpoints of GovernanceController (`/api/governance/**`). Write
 * operations (register/activate/draft/approve) exist on the backend but are administrative
 * governance-workflow actions, out of scope for the employee-facing read views this frontend
 * implements (brief FASE 15 section 19/62) - not omitted by oversight.
 */
@Injectable({ providedIn: 'root' })
export class GovernanceService {
  private readonly http = inject(HttpClient);

  listAiSystems(): Observable<AiSystemResponse[]> {
    return this.http.get<AiSystemResponse[]>(`${API_BASE_URL}/governance/ai-systems`);
  }

  listModels(aiSystemId: string): Observable<ModelResponse[]> {
    return this.http.get<ModelResponse[]>(
      `${API_BASE_URL}/governance/ai-systems/${encodeURIComponent(aiSystemId)}/models`,
    );
  }

  listPrompts(aiSystemId: string): Observable<PromptResponse[]> {
    return this.http.get<PromptResponse[]>(
      `${API_BASE_URL}/governance/ai-systems/${encodeURIComponent(aiSystemId)}/prompts`,
    );
  }

  getActivePrompt(promptKey: string): Observable<PromptResponse> {
    return this.http.get<PromptResponse>(
      `${API_BASE_URL}/governance/prompts/${encodeURIComponent(promptKey)}/active`,
    );
  }

  listRiskAssessments(aiSystemId: string): Observable<RiskAssessmentResponse[]> {
    return this.http.get<RiskAssessmentResponse[]>(
      `${API_BASE_URL}/governance/ai-systems/${encodeURIComponent(aiSystemId)}/risk-assessments`,
    );
  }
}

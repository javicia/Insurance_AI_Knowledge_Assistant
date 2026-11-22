import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../config/api.config';
import type { ChatRequest, RagAnswer } from '../models/chat.model';

/** Consumes exactly `POST /api/chat` (ChatController) - no invented endpoints. */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);

  ask(request: ChatRequest): Observable<RagAnswer> {
    return this.http.post<RagAnswer>(`${API_BASE_URL}/chat`, request);
  }
}

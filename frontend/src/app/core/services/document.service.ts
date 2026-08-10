import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../config/api.config';
import type { DocumentResponse, UploadDocumentRequest } from '../models/document.model';

/** Consumes exactly `POST /api/documents` and `GET /api/documents/{id}` (DocumentController). */
@Injectable({ providedIn: 'root' })
export class DocumentService {
  private readonly http = inject(HttpClient);

  upload(request: UploadDocumentRequest): Observable<DocumentResponse> {
    const formData = new FormData();
    formData.append('file', request.file, request.file.name);
    formData.append('name', request.name);
    formData.append('type', request.type);
    formData.append('classification', request.classification);
    if (request.product) {
      formData.append('product', request.product);
    }
    if (request.country) {
      formData.append('country', request.country);
    }
    if (request.language) {
      formData.append('language', request.language);
    }
    return this.http.post<DocumentResponse>(`${API_BASE_URL}/documents`, formData);
  }

  getById(id: string): Observable<DocumentResponse> {
    return this.http.get<DocumentResponse>(`${API_BASE_URL}/documents/${encodeURIComponent(id)}`);
  }
}

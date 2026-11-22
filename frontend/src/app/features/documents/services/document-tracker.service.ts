import { Injectable, inject, signal } from '@angular/core';
import { Subscription, interval, switchMap, takeWhile } from 'rxjs';

import { DocumentService } from '../../../core/services/document.service';
import type { DocumentResponse, UploadDocumentRequest } from '../../../core/models/document.model';

const POLL_INTERVAL_MS = 3_000;
const TERMINAL_STATUSES = new Set(['EMBEDDED', 'FAILED']);

function isTerminal(document: DocumentResponse): boolean {
  const latestVersion = document.versions[document.versions.length - 1];
  return latestVersion ? TERMINAL_STATUSES.has(latestVersion.status) : true;
}

/**
 * Tracks documents uploaded during this browser session (brief FASE 15 section 17). The backend
 * has no "list all documents" endpoint (only `POST /api/documents` and `GET /api/documents/{id}`
 * - verified against DocumentController, not assumed) so this is deliberately a session-scoped
 * upload log, not a persisted document library - polling `GET /api/documents/{id}` is the only
 * real way to observe a version's status progress from UPLOADED through EMBEDDED/FAILED.
 */
@Injectable({ providedIn: 'root' })
export class DocumentTrackerService {
  private readonly documentService = inject(DocumentService);

  private readonly uploadsSignal = signal<readonly DocumentResponse[]>([]);
  readonly uploads = this.uploadsSignal.asReadonly();

  private readonly uploadingSignal = signal(false);
  readonly uploading = this.uploadingSignal.asReadonly();

  private readonly pollSubscriptions = new Map<string, Subscription>();

  upload(request: UploadDocumentRequest): void {
    this.uploadingSignal.set(true);
    this.documentService.upload(request).subscribe({
      next: (document) => {
        this.uploadingSignal.set(false);
        this.uploadsSignal.update((existing) => [document, ...existing]);
        if (!isTerminal(document)) {
          this.startPolling(document.id);
        }
      },
      error: () => this.uploadingSignal.set(false),
    });
  }

  private startPolling(documentId: string): void {
    this.pollSubscriptions.get(documentId)?.unsubscribe();

    const subscription = interval(POLL_INTERVAL_MS)
      .pipe(
        switchMap(() => this.documentService.getById(documentId)),
        takeWhile((document) => !isTerminal(document), true),
      )
      .subscribe((document) => {
        this.uploadsSignal.update((existing) =>
          existing.map((entry) => (entry.id === document.id ? document : entry)),
        );
        if (isTerminal(document)) {
          this.pollSubscriptions.get(documentId)?.unsubscribe();
          this.pollSubscriptions.delete(documentId);
        }
      });

    this.pollSubscriptions.set(documentId, subscription);
  }
}

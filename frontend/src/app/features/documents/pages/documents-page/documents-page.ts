import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { DocumentUpload } from '../../components/document-upload/document-upload';
import { DocumentTrackerService } from '../../services/document-tracker.service';
import { documentStatusVariant } from '../../utils/document-status.util';
import { EmptyState } from '../../../../shared/components/empty-state/empty-state';
import { StatusBadge } from '../../../../shared/components/status-badge/status-badge';
import type { DocumentResponse, DocumentStatus, UploadDocumentRequest } from '../../../../core/models/document.model';

@Component({
  selector: 'app-documents-page',
  imports: [DocumentUpload, StatusBadge, EmptyState],
  templateUrl: './documents-page.html',
  styleUrl: './documents-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocumentsPage {
  protected readonly tracker = inject(DocumentTrackerService);
  protected readonly documentStatusVariant = documentStatusVariant;

  protected onUploadRequested(request: UploadDocumentRequest): void {
    this.tracker.upload(request);
  }

  protected latestStatus(document: DocumentResponse): DocumentStatus {
    return this.latestVersionNumber(document) !== '—'
      ? document.versions[document.versions.length - 1].status
      : 'UPLOADED';
  }

  protected latestVersionNumber(document: DocumentResponse): string {
    const versions = document.versions;
    return versions.length > 0 ? versions[versions.length - 1].versionNumber : '—';
  }

  protected trackById(_index: number, document: DocumentResponse): string {
    return document.id;
  }
}

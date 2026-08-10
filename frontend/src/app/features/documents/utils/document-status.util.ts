import type { StatusBadgeVariant } from '../../../shared/components/status-badge/status-badge';
import type { DocumentStatus } from '../../../core/models/document.model';

export function documentStatusVariant(status: DocumentStatus): StatusBadgeVariant {
  switch (status) {
    case 'EMBEDDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'PROCESSING':
    case 'PROCESSED':
      return 'info';
    case 'UPLOADED':
    default:
      return 'neutral';
  }
}

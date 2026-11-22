import type { StatusBadgeVariant } from '../../../shared/components/status-badge/status-badge';
import type { RiskClassification } from '../../../core/models/governance.model';

export function riskVariant(classification: RiskClassification): StatusBadgeVariant {
  switch (classification) {
    case 'MINIMAL':
      return 'success';
    case 'LIMITED':
      return 'info';
    case 'HIGH':
      return 'warning';
    case 'UNACCEPTABLE':
      return 'danger';
  }
}

export function lifecycleVariant(status: string): StatusBadgeVariant {
  switch (status) {
    case 'ACTIVE':
    case 'APPROVED':
      return 'success';
    case 'RETIRED':
    case 'DEACTIVATED':
      return 'neutral';
    case 'DRAFT':
    default:
      return 'info';
  }
}

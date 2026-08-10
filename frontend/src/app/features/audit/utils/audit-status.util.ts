import type { StatusBadgeVariant } from '../../../shared/components/status-badge/status-badge';
import type { AuditOutcome } from '../../../core/models/audit.model';

export function auditOutcomeVariant(outcome: AuditOutcome): StatusBadgeVariant {
  switch (outcome) {
    case 'GROUNDED_ANSWER':
      return 'success';
    case 'NO_ANSWER':
      return 'neutral';
    case 'BLOCKED_BY_GUARDRAIL':
      return 'warning';
    case 'ERROR':
      return 'danger';
  }
}

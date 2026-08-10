import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatExpansionModule } from '@angular/material/expansion';

/**
 * Collapsible technical panel (brief FASE 15 section 6/14/25) - trace id and error code are
 * available for support/debugging but never shown permanently to the end user.
 */
@Component({
  selector: 'app-technical-details',
  imports: [MatExpansionModule],
  templateUrl: './technical-details.html',
  styleUrl: './technical-details.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TechnicalDetails {
  readonly traceId = input<string | null>(null);
  readonly code = input<string | null>(null);
}

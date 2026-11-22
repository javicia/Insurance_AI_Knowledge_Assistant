import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

import type { SourceReference } from '../../../../core/models/chat.model';

/**
 * A single citation (brief FASE 15 section 12/44) - every field displayed is copied straight
 * from the real `SourceReference` the backend returned; page/section are only rendered when
 * present (nullable), never defaulted to a placeholder. There is no "View source" / "Open
 * document" action here since the backend exposes no document-content-download endpoint (brief
 * section 62) - inventing that button would be exactly the "no-op button" the brief forbids.
 */
@Component({
  selector: 'app-citation-card',
  imports: [MatIconModule],
  templateUrl: './citation-card.html',
  styleUrl: './citation-card.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CitationCard {
  readonly source = input.required<SourceReference>();
}

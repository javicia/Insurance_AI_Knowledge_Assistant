import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { CitationCard } from '../citation-card/citation-card';
import type { SourceReference } from '../../../../core/models/chat.model';

/** Renders every real citation returned for a grounded answer (brief FASE 15 section 12). */
@Component({
  selector: 'app-citation-list',
  imports: [CitationCard],
  templateUrl: './citation-list.html',
  styleUrl: './citation-list.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CitationList {
  readonly sources = input.required<readonly SourceReference[]>();

  protected trackByChunkId(_index: number, source: SourceReference): string {
    return source.chunkId;
  }
}

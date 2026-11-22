import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

import { CitationList } from '../citation-list/citation-list';
import { SecurityBanner } from '../security-banner/security-banner';
import { TechnicalDetails } from '../../../../shared/components/technical-details/technical-details';
import type { ChatMessage } from '../../models/chat-message.model';

/**
 * Renders one turn of the conversation. Every non-grounded state (no-answer, blocked, error) has
 * its own professional visual treatment (brief FASE 15 section 13/14/25) - never a bare "I don't
 * know" or a raw error message.
 */
@Component({
  selector: 'app-message-bubble',
  imports: [MatIconModule, CitationList, SecurityBanner, TechnicalDetails],
  templateUrl: './message-bubble.html',
  styleUrl: './message-bubble.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessageBubble {
  readonly message = input.required<ChatMessage>();
}

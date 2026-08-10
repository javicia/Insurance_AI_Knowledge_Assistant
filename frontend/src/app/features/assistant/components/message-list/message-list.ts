import { AfterViewChecked, ChangeDetectionStrategy, Component, ElementRef, input, viewChild } from '@angular/core';

import { LoadingIndicator } from '../../../../shared/components/loading-indicator/loading-indicator';
import { MessageBubble } from '../message-bubble/message-bubble';
import type { ChatMessage } from '../../models/chat-message.model';

@Component({
  selector: 'app-message-list',
  imports: [MessageBubble, LoadingIndicator],
  templateUrl: './message-list.html',
  styleUrl: './message-list.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessageList implements AfterViewChecked {
  readonly messages = input.required<readonly ChatMessage[]>();
  readonly sending = input(false);

  private readonly scrollAnchor = viewChild<ElementRef<HTMLElement>>('scrollAnchor');
  private lastRenderedCount = 0;

  protected trackById(_index: number, message: ChatMessage): string {
    return message.id;
  }

  ngAfterViewChecked(): void {
    const currentCount = this.messages().length + (this.sending() ? 1 : 0);
    if (currentCount !== this.lastRenderedCount) {
      this.lastRenderedCount = currentCount;
      // scrollIntoView is absent in some minimal DOM environments (e.g. jsdom in unit tests) -
      // guarded rather than assumed, not a test-only workaround.
      this.scrollAnchor()?.nativeElement.scrollIntoView?.({ behavior: 'smooth', block: 'end' });
    }
  }
}

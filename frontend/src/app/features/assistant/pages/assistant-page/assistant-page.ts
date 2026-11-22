import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { ChatComposer } from '../../components/chat-composer/chat-composer';
import { MessageList } from '../../components/message-list/message-list';
import { ChatSessionService } from '../../services/chat-session.service';

@Component({
  selector: 'app-assistant-page',
  imports: [MatButtonModule, MatIconModule, MessageList, ChatComposer],
  templateUrl: './assistant-page.html',
  styleUrl: './assistant-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssistantPage {
  protected readonly session = inject(ChatSessionService);

  protected onSend(question: string): void {
    this.session.send(question);
  }

  protected onNewChat(): void {
    this.session.startNewChat();
  }
}

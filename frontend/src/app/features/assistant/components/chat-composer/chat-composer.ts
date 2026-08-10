import { ChangeDetectionStrategy, Component, ElementRef, computed, input, output, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * The question input (brief FASE 15 section 10/43): Enter sends, Shift+Enter inserts a newline,
 * disabled while a request is in flight so a user cannot accidentally fire concurrent requests.
 */
@Component({
  selector: 'app-chat-composer',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './chat-composer.html',
  styleUrl: './chat-composer.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatComposer {
  readonly disabled = input(false);
  readonly send = output<string>();

  protected readonly value = signal('');
  protected readonly canSend = computed(() => this.value().trim().length > 0 && !this.disabled());

  private readonly textarea = viewChild<ElementRef<HTMLTextAreaElement>>('textarea');

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.submit();
    }
  }

  protected submit(): void {
    if (!this.canSend()) {
      return;
    }
    this.send.emit(this.value());
    this.value.set('');
    this.textarea()?.nativeElement.focus();
  }
}

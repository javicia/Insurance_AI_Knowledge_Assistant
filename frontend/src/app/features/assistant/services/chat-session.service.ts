import { Injectable, inject, signal } from '@angular/core';

import { ChatService } from '../../../core/services/chat.service';
import type { ApiError } from '../../../core/models/api-error.model';
import type { RagAnswer } from '../../../core/models/chat.model';
import type { AssistantMessage, AssistantMessageKind, ChatMessage } from '../models/chat-message.model';

function generateMessageId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `msg-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * Owns the in-memory conversation state for the Assistant page (brief FASE 15 section 29:
 * signals + a local feature service, no NgRx). Conversations are never persisted to
 * localStorage/sessionStorage (brief section 15/30) - refreshing the page starts a new session,
 * which is the correct default for a tool that may surface PII copied from source documents.
 */
@Injectable({ providedIn: 'root' })
export class ChatSessionService {
  private readonly chatService = inject(ChatService);

  private readonly messagesSignal = signal<readonly ChatMessage[]>([]);
  readonly messages = this.messagesSignal.asReadonly();

  private readonly sendingSignal = signal(false);
  readonly sending = this.sendingSignal.asReadonly();

  /** Guards against accidental concurrent requests (brief FASE 15 section 43). */
  send(question: string): void {
    const trimmed = question.trim();
    if (!trimmed || this.sendingSignal()) {
      return;
    }

    this.appendMessage({ id: generateMessageId(), role: 'user', text: trimmed });
    this.sendingSignal.set(true);

    this.chatService.ask({ question: trimmed }).subscribe({
      next: (answer) => {
        this.appendMessage(this.toAssistantMessage(answer));
        this.sendingSignal.set(false);
      },
      error: (error: ApiError) => {
        this.appendMessage(this.toErrorMessage(error));
        this.sendingSignal.set(false);
      },
    });
  }

  startNewChat(): void {
    this.messagesSignal.set([]);
  }

  private appendMessage(message: ChatMessage): void {
    this.messagesSignal.update((messages) => [...messages, message]);
  }

  private toAssistantMessage(answer: RagAnswer): AssistantMessage {
    return {
      id: generateMessageId(),
      role: 'assistant',
      kind: this.kindOf(answer),
      text: answer.answer,
      sources: answer.sources,
      traceId: answer.traceId,
      piiDetected: answer.piiDetected,
    };
  }

  /**
   * A total mapping over `AnswerMode`, deliberately exhaustive rather than defaulting: if the
   * backend ever adds a fifth mode, this must fail to compile instead of silently rendering it
   * as a grounded answer - which for an unverified answer would be the worst possible default.
   */
  private kindOf(answer: RagAnswer): AssistantMessageKind {
    switch (answer.answerMode) {
      case 'DOCUMENT_GROUNDED':
        return 'grounded';
      case 'GENERAL_AI_UNVERIFIED':
        return 'general-ai';
      case 'NO_ANSWER':
        return 'no-answer';
      case 'BLOCKED':
        return 'blocked';
    }
  }

  private toErrorMessage(error: ApiError): AssistantMessage {
    return {
      id: generateMessageId(),
      role: 'assistant',
      kind: 'error',
      text: error.message,
      sources: [],
      traceId: error.traceId,
      piiDetected: false,
      errorCode: error.code,
    };
  }
}

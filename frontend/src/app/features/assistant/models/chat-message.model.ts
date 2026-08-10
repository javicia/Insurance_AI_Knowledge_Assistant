import type { SourceReference } from '../../../core/models/chat.model';

/**
 * Client-side conversation model - not a backend DTO. Built entirely from real `RagAnswer`/
 * `ApiError` fields (see ChatSessionService); `kind` is derived from `RagAnswer.grounding.status`
 * and `RagAnswer.blocked`, never guessed from message text.
 */
export type AssistantMessageKind = 'grounded' | 'no-answer' | 'blocked' | 'error';

export interface UserMessage {
  readonly id: string;
  readonly role: 'user';
  readonly text: string;
}

export interface AssistantMessage {
  readonly id: string;
  readonly role: 'assistant';
  readonly kind: AssistantMessageKind;
  readonly text: string;
  readonly sources: readonly SourceReference[];
  readonly traceId: string | null;
  readonly piiDetected: boolean;
  readonly errorCode?: string | null;
}

export type ChatMessage = UserMessage | AssistantMessage;

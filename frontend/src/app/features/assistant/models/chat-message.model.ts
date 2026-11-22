import type { SourceReference } from '../../../core/models/chat.model';

/**
 * Client-side conversation model - not a backend DTO. Built entirely from real `RagAnswer`/
 * `ApiError` fields (see ChatSessionService); `kind` maps 1:1 from `RagAnswer.answerMode`, never
 * guessed from message text.
 *
 * `'error'` is the only kind with no `answerMode` counterpart: it represents a transport or
 * server failure where no `RagAnswer` was received at all, which is a different thing from any
 * answer the backend can return.
 */
export type AssistantMessageKind =
  | 'grounded'
  | 'general-ai'
  | 'no-answer'
  | 'blocked'
  | 'error';

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

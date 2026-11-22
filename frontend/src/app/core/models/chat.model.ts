/**
 * Mirrors the real backend contract exactly (verified against
 * com.rag.springai.insuranceai.adapters.inbound.rest.{ChatRequest,ChatFilterRequest} and
 * com.rag.springai.insuranceai.application.rag.{RagAnswer,SourceReference,Grounding} - not
 * invented). See docs/frontend/FRONTEND_ARCHITECTURE.md.
 */

export type DocumentType = 'POLICY' | 'CLAIMS_PROCEDURE' | 'CORPORATE';

export type DocumentClassification = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED';

export interface ChatFilterRequest {
  readonly documentId?: string | null;
  readonly documentVersionId?: string | null;
  readonly documentType?: DocumentType | null;
  readonly documentClassification?: DocumentClassification | null;
  readonly page?: number | null;
  readonly section?: string | null;
}

export interface ChatRequest {
  readonly question: string;
  readonly filters?: ChatFilterRequest | null;
}

export type GroundingStatus = 'GROUNDED' | 'NOT_GROUNDED';

export interface Grounding {
  readonly status: GroundingStatus;
}

export interface SourceReference {
  readonly documentId: string;
  readonly document: string;
  readonly version: string;
  readonly page: number | null;
  readonly section: string | null;
  readonly chunkId: string;
}

/**
 * Where the answer's content came from (backend FASE 28, see AnswerMode.java). Replaces the
 * earlier `blocked` boolean, which could express only two of these four states and had to be
 * read together with `grounding.status` to mean anything.
 *
 * `GENERAL_AI_UNVERIFIED` is an answer written from the language model's own general knowledge,
 * because the documentation held no sufficient evidence. It never carries citations and must be
 * rendered with a visible warning: an employee cannot tell a policy fact from a model's general
 * knowledge unless the UI says so.
 */
export type AnswerMode =
  | 'DOCUMENT_GROUNDED'
  | 'GENERAL_AI_UNVERIFIED'
  | 'NO_ANSWER'
  | 'BLOCKED';

/**
 * `answerMode` and `piiDetected` are genuine backend response fields (see RagAnswer.java's own
 * Javadoc), never derived or guessed client-side from message text.
 */
export interface RagAnswer {
  readonly answer: string;
  readonly sources: readonly SourceReference[];
  readonly grounding: Grounding;
  readonly answerMode: AnswerMode;
  readonly traceId: string;
  readonly piiDetected: boolean;
}

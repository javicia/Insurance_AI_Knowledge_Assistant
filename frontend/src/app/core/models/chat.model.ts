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
 * `blocked` and `piiDetected` were added to the backend during FASE 15 frontend integration
 * (see RagAnswer.java's own Javadoc) - both are genuine response fields, not derived/guessed
 * client-side from message text.
 */
export interface RagAnswer {
  readonly answer: string;
  readonly sources: readonly SourceReference[];
  readonly grounding: Grounding;
  readonly traceId: string;
  readonly piiDetected: boolean;
  readonly blocked: boolean;
}

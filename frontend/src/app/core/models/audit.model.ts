/**
 * Mirrors adapters.inbound.rest.governance.AuditRecordResponse and the domain enums it
 * serializes (RetrievalOutcome, AuditOutcome) exactly. Deliberately has no field for raw
 * question/answer/chunk text - the backend never stores or returns it (data minimization).
 */

export type RetrievalOutcome = 'HYBRID' | 'SEMANTIC_ONLY' | 'LEXICAL_ONLY';
export type AuditOutcome = 'GROUNDED_ANSWER' | 'NO_ANSWER' | 'BLOCKED_BY_GUARDRAIL' | 'ERROR';

export interface AuditRecordResponse {
  readonly id: string;
  readonly traceId: string;
  readonly timestamp: string;
  readonly aiSystemId: string;
  readonly provider: string;
  readonly promptKey: string | null;
  readonly promptVersion: number | null;
  readonly retrievalOutcome: RetrievalOutcome | null;
  readonly semanticCandidateCount: number;
  readonly lexicalCandidateCount: number;
  readonly finalCandidateCount: number;
  readonly groundingStatus: string | null;
  readonly promptInjectionDetected: boolean;
  readonly piiDetectedInQuestion: boolean;
  readonly piiDetectedInAnswer: boolean;
  readonly latencyMs: number;
  readonly outcome: AuditOutcome;
  readonly errorClassification: string | null;
}

import type { DocumentClassification, DocumentType } from './chat.model';

/**
 * Mirrors com.rag.springai.insuranceai.domain.document.DocumentStatus and
 * adapters.inbound.rest.DocumentResponse exactly.
 */
export type DocumentStatus = 'UPLOADED' | 'PROCESSING' | 'PROCESSED' | 'EMBEDDED' | 'FAILED';

export interface DocumentVersionResponse {
  readonly id: string;
  readonly versionNumber: string;
  readonly status: DocumentStatus;
  readonly contentHash: string;
}

export interface DocumentResponse {
  readonly id: string;
  readonly name: string;
  readonly type: DocumentType;
  readonly versions: readonly DocumentVersionResponse[];
}

export interface UploadDocumentRequest {
  readonly file: File;
  readonly name: string;
  readonly type: DocumentType;
  readonly classification: DocumentClassification;
  readonly product?: string;
  readonly country?: string;
  readonly language?: string;
}

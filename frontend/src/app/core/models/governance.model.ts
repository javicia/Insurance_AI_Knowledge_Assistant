/**
 * Mirrors adapters.inbound.rest.governance.{AiSystemResponse,ModelResponse,PromptResponse,
 * RiskAssessmentResponse} and the domain enums they serialize exactly.
 */

export type RiskClassification = 'MINIMAL' | 'LIMITED' | 'HIGH' | 'UNACCEPTABLE';
export type AiSystemStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED';
export type ModelStatus = 'ACTIVE' | 'DEACTIVATED';
export type PromptStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED';
export type RiskAssessmentStatus = 'DRAFT' | 'APPROVED';

export interface HumanOversightResponse {
  readonly required: boolean;
  readonly whenRequired: string;
  readonly escalationCondition: string;
  readonly decisionResponsibility: string;
}

export interface AiSystemResponse {
  readonly id: string;
  readonly name: string;
  readonly purpose: string;
  readonly owner: string;
  readonly intendedUse: string;
  readonly prohibitedUse: string;
  readonly riskClassification: RiskClassification;
  readonly status: AiSystemStatus;
  readonly humanOversight: HumanOversightResponse;
}

export interface ModelResponse {
  readonly id: string;
  readonly aiSystemId: string;
  readonly provider: string;
  readonly modelIdentifier: string;
  readonly version: string | null;
  readonly capabilities: string;
  readonly intendedPurpose: string;
  readonly status: ModelStatus;
}

export interface PromptResponse {
  readonly id: string;
  readonly aiSystemId: string;
  readonly promptKey: string;
  readonly version: number;
  readonly content: string;
  readonly checksum: string;
  readonly status: PromptStatus;
  readonly effectiveDate: string;
  readonly author: string;
  readonly changeReason: string;
}

export interface RiskAssessmentResponse {
  readonly id: string;
  readonly aiSystemId: string;
  readonly classification: RiskClassification;
  readonly rationale: string;
  readonly controls: string;
  readonly residualRisk: string;
  readonly reviewer: string;
  readonly assessmentDate: string;
  readonly status: RiskAssessmentStatus;
}

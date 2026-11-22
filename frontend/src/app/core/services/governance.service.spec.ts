import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { GovernanceService } from './governance.service';
import type { AiSystemResponse } from '../models/governance.model';

describe('GovernanceService', () => {
  let service: GovernanceService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(GovernanceService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists AI systems from GET /api/governance/ai-systems', () => {
    const systems: AiSystemResponse[] = [
      {
        id: 'sys-1',
        name: 'Insurance Knowledge Assistant',
        purpose: 'Internal knowledge assistance',
        owner: 'AI Platform Team',
        intendedUse: 'Answer questions',
        prohibitedUse: 'Automated decisions',
        riskClassification: 'LIMITED',
        status: 'ACTIVE',
        humanOversight: {
          required: true,
          whenRequired: 'Always',
          escalationCondition: 'Any customer-specific question',
          decisionResponsibility: 'Claims team',
        },
      },
    ];

    let received: AiSystemResponse[] | undefined;
    service.listAiSystems().subscribe((response) => (received = response));

    httpMock.expectOne('/api/governance/ai-systems').flush(systems);

    expect(received).toEqual(systems);
  });

  it('lists models for an AI system from GET /api/governance/ai-systems/{id}/models', () => {
    service.listModels('sys-1').subscribe();
    const req = httpMock.expectOne('/api/governance/ai-systems/sys-1/models');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('gets the active prompt from GET /api/governance/prompts/{key}/active', () => {
    service.getActivePrompt('insurance-rag-system-prompt').subscribe();
    const req = httpMock.expectOne('/api/governance/prompts/insurance-rag-system-prompt/active');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });
});

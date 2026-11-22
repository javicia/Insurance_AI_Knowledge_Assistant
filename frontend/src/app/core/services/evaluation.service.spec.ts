import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { EvaluationService } from './evaluation.service';

describe('EvaluationService', () => {
  let service: EvaluationService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(EvaluationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs to /api/evaluation/runs to run the built-in dataset', () => {
    service.runBuiltInDataset().subscribe();
    const req = httpMock.expectOne('/api/evaluation/runs');
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('gets a run by id', () => {
    service.getById('run-1').subscribe();
    const req = httpMock.expectOne('/api/evaluation/runs/run-1');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('lists recent runs with a limit', () => {
    service.listRecent(5).subscribe();
    const req = httpMock.expectOne((r) => r.url === '/api/evaluation/runs/recent');
    expect(req.request.params.get('limit')).toBe('5');
    req.flush([]);
  });
});

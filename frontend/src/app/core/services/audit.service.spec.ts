import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { AuditService } from './audit.service';

describe('AuditService', () => {
  let service: AuditService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AuditService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fetches an audit record by trace id', () => {
    service.getByTraceId('trace-1').subscribe();
    const req = httpMock.expectOne('/api/audit/traces/trace-1');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('lists recent audit records with the given limit', () => {
    service.listRecent(10).subscribe();
    const req = httpMock.expectOne((r) => r.url === '/api/audit/recent');
    expect(req.request.params.get('limit')).toBe('10');
    req.flush([]);
  });

  it('defaults the limit to 20', () => {
    service.listRecent().subscribe();
    const req = httpMock.expectOne((r) => r.url === '/api/audit/recent');
    expect(req.request.params.get('limit')).toBe('20');
    req.flush([]);
  });
});

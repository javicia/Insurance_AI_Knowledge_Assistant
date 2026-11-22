import { HttpClient, HttpHeaders, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { traceInterceptor } from './trace.interceptor';
import { TraceContextService } from '../services/trace-context.service';

describe('traceInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let traceContext: TraceContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([traceInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    traceContext = TestBed.inject(TraceContextService);
  });

  afterEach(() => httpMock.verify());

  it('records the X-Trace-Id response header into TraceContextService', () => {
    http.get('/api/chat').subscribe();

    httpMock
      .expectOne('/api/chat')
      .flush({}, { headers: new HttpHeaders({ 'X-Trace-Id': 'trace-123' }) });

    expect(traceContext.lastTraceId()).toBe('trace-123');
  });

  it('leaves the last trace id unchanged when a response has no trace header', () => {
    http.get('/api/chat').subscribe();
    httpMock.expectOne('/api/chat').flush({});

    expect(traceContext.lastTraceId()).toBeNull();
  });
});

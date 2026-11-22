import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';

import { errorInterceptor } from './error.interceptor';
import { AuthService } from '../auth/auth.service';
import { authServiceStub } from '../auth/testing/auth-service-stub';
import { TraceContextService } from '../services/trace-context.service';
import type { ApiError } from '../models/api-error.model';

describe('errorInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let traceContext: TraceContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authServiceStub() },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    traceContext = TestBed.inject(TraceContextService);
  });

  afterEach(() => httpMock.verify());

  it('passes through the real backend ErrorResponse shape (code/message/traceId)', () => {
    let error: ApiError | undefined;
    http.get('/api/chat').subscribe({ error: (e) => (error = e) });

    httpMock
      .expectOne('/api/chat')
      .flush(
        { code: 'DOCUMENT_NOT_FOUND', message: 'No document found for id doc-1', traceId: 'trace-1' },
        { status: 422, statusText: 'Unprocessable Entity' },
      );

    expect(error).toEqual({
      status: 422,
      code: 'DOCUMENT_NOT_FOUND',
      message: 'No document found for id doc-1',
      traceId: 'trace-1',
    });
    expect(traceContext.lastTraceId()).toBe('trace-1');
  });

  it('falls back to a friendly message for a malformed (non-ErrorResponse) body', () => {
    let error: ApiError | undefined;
    http.get('/api/chat').subscribe({ error: (e) => (error = e) });

    httpMock
      .expectOne('/api/chat')
      .flush({ timestamp: '...', status: 400, error: 'Bad Request', path: '/api/chat' }, { status: 400, statusText: 'Bad Request' });

    expect(error?.code).toBe('HTTP_400');
    expect(error?.message).toBe('The request could not be understood by the server.');
  });

  it('maps a network-level failure (status 0) to a NETWORK_ERROR', () => {
    let error: ApiError | undefined;
    http.get('/api/chat').subscribe({ error: (e) => (error = e) });

    httpMock.expectOne('/api/chat').error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });

    expect(error?.code).toBe('NETWORK_ERROR');
    expect(error?.status).toBe(0);
  });

  it('never leaks a raw stack trace or Java exception name to the caller', () => {
    let error: ApiError | undefined;
    http.get('/api/chat').subscribe({ error: (e) => (error = e) });

    httpMock.expectOne('/api/chat').flush('Internal Server Error', { status: 500, statusText: 'Internal Server Error' });

    expect(error?.message).not.toContain('Exception');
    expect(error?.message).not.toContain('at com.rag');
  });

  it('re-authenticates (redirects to login) on a 401 for a previously-authenticated session', () => {
    const login = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: authServiceStub({ isAuthenticated: (() => true) as unknown as AuthService['isAuthenticated'], login }),
        },
      ],
    });
    const localHttp = TestBed.inject(HttpClient);
    const localHttpMock = TestBed.inject(HttpTestingController);

    localHttp.get('/api/chat').subscribe({ error: () => {} });
    localHttpMock.expectOne('/api/chat').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(login).toHaveBeenCalledTimes(1);
    localHttpMock.verify();
  });

  it('does not attempt to re-authenticate a 401 for an already-anonymous session', () => {
    const login = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: authServiceStub({ isAuthenticated: (() => false) as unknown as AuthService['isAuthenticated'], login }),
        },
      ],
    });
    const localHttp = TestBed.inject(HttpClient);
    const localHttpMock = TestBed.inject(HttpTestingController);

    localHttp.get('/api/chat').subscribe({ error: () => {} });
    localHttpMock.expectOne('/api/chat').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(login).not.toHaveBeenCalled();
    localHttpMock.verify();
  });
});

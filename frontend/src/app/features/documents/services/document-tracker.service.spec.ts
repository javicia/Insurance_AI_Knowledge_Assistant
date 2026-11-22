import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DocumentTrackerService } from './document-tracker.service';
import type { DocumentResponse, UploadDocumentRequest } from '../../../core/models/document.model';

function uploadRequest(): UploadDocumentRequest {
  return {
    file: new File(['%PDF-1.4'], 'policy.pdf', { type: 'application/pdf' }),
    name: 'Home Policy',
    type: 'POLICY',
    classification: 'INTERNAL',
  };
}

function documentResponse(status: DocumentResponse['versions'][number]['status']): DocumentResponse {
  return {
    id: 'doc-1',
    name: 'Home Policy',
    type: 'POLICY',
    versions: [{ id: 'v1', versionNumber: '1.0', status, contentHash: 'abc' }],
  };
}

describe('DocumentTrackerService', () => {
  let service: DocumentTrackerService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(DocumentTrackerService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    vi.useRealTimers();
  });

  it('adds the uploaded document to the session list', () => {
    service.upload(uploadRequest());

    httpMock.expectOne('/api/documents').flush(documentResponse('EMBEDDED'));

    expect(service.uploads().length).toBe(1);
    expect(service.uploads()[0].id).toBe('doc-1');
    expect(service.uploading()).toBe(false);
  });

  it('does not poll further once the initial upload response is already terminal (EMBEDDED)', async () => {
    vi.useFakeTimers();

    service.upload(uploadRequest());
    httpMock.expectOne('/api/documents').flush(documentResponse('EMBEDDED'));

    await vi.advanceTimersByTimeAsync(10_000);
    httpMock.expectNone('/api/documents/doc-1');
  });

  it('polls GET /api/documents/{id} until the status becomes terminal', async () => {
    vi.useFakeTimers();

    service.upload(uploadRequest());
    httpMock.expectOne('/api/documents').flush(documentResponse('UPLOADED'));

    await vi.advanceTimersByTimeAsync(3_000);
    httpMock.expectOne('/api/documents/doc-1').flush(documentResponse('PROCESSED'));

    await vi.advanceTimersByTimeAsync(3_000);
    httpMock.expectOne('/api/documents/doc-1').flush(documentResponse('EMBEDDED'));

    expect(service.uploads()[0].versions[0].status).toBe('EMBEDDED');

    await vi.advanceTimersByTimeAsync(10_000);
    httpMock.expectNone('/api/documents/doc-1');
  });
});

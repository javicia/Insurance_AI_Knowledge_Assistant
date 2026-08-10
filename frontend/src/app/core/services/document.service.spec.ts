import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { DocumentService } from './document.service';
import type { DocumentResponse } from '../models/document.model';

describe('DocumentService', () => {
  let service: DocumentService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(DocumentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('uploads a document as multipart form data to POST /api/documents', () => {
    const file = new File(['%PDF-1.4'], 'policy.pdf', { type: 'application/pdf' });
    const expected: DocumentResponse = {
      id: 'doc-1',
      name: 'Home Policy',
      type: 'POLICY',
      versions: [{ id: 'v1', versionNumber: '1.0', status: 'UPLOADED', contentHash: 'abc' }],
    };

    let received: DocumentResponse | undefined;
    service
      .upload({ file, name: 'Home Policy', type: 'POLICY', classification: 'INTERNAL' })
      .subscribe((response) => (received = response));

    const req = httpMock.expectOne('/api/documents');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBe(true);
    req.flush(expected);

    expect(received).toEqual(expected);
  });

  it('fetches a document by id from GET /api/documents/{id}', () => {
    const expected: DocumentResponse = {
      id: 'doc-1',
      name: 'Home Policy',
      type: 'POLICY',
      versions: [{ id: 'v1', versionNumber: '1.0', status: 'EMBEDDED', contentHash: 'abc' }],
    };

    let received: DocumentResponse | undefined;
    service.getById('doc-1').subscribe((response) => (received = response));

    const req = httpMock.expectOne('/api/documents/doc-1');
    expect(req.request.method).toBe('GET');
    req.flush(expected);

    expect(received).toEqual(expected);
  });
});

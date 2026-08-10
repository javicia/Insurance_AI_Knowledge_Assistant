import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { ChatService } from './chat.service';
import type { RagAnswer } from '../models/chat.model';

describe('ChatService', () => {
  let service: ChatService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ChatService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('posts the question to /api/chat and returns the real RagAnswer shape', () => {
    const expected: RagAnswer = {
      answer: 'Water damage is covered.',
      sources: [
        { documentId: 'doc-1', document: 'Home Policy', version: '1.0', page: 1, section: null, chunkId: 'chunk-1' },
      ],
      grounding: { status: 'GROUNDED' },
      traceId: 'trace-1',
      piiDetected: false,
      blocked: false,
    };

    let received: RagAnswer | undefined;
    service.ask({ question: 'Is water damage covered?' }).subscribe((answer) => (received = answer));

    const req = httpMock.expectOne('/api/chat');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ question: 'Is water damage covered?' });
    req.flush(expected);

    expect(received).toEqual(expected);
  });
});

import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { AssistantPage } from './assistant-page';
import { errorInterceptor } from '../../../../core/interceptors/error.interceptor';
import type { RagAnswer } from '../../../../core/models/chat.model';
import { AuthService } from '../../../../core/auth/auth.service';
import { authServiceStub } from '../../../../core/auth/testing/auth-service-stub';

describe('AssistantPage', () => {
  let fixture: ComponentFixture<AssistantPage>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AssistantPage],
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: AuthService, useValue: authServiceStub() },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AssistantPage);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('shows the intro state before any question is asked', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('How can I help today?');
  });

  it('sends a question through the composer and renders the grounded answer', () => {
    fixture.detectChanges();

    const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('textarea');
    textarea.value = 'Is water damage covered?';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();

    const answer: RagAnswer = {
      answer: 'Water damage is covered up to the policy limit.',
      sources: [],
      grounding: { status: 'GROUNDED' },
      traceId: 'trace-1',
      piiDetected: false,
      blocked: false,
    };
    httpMock.expectOne('/api/chat').flush(answer);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Water damage is covered up to the policy limit.');
  });
});

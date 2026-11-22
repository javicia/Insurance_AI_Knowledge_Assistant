import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { MessageBubble } from './message-bubble';
import type { AssistantMessage, UserMessage } from '../../models/chat-message.model';

describe('MessageBubble', () => {
  let fixture: ComponentFixture<MessageBubble>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MessageBubble],
      providers: [provideNoopAnimations()],
    }).compileComponents();
    fixture = TestBed.createComponent(MessageBubble);
  });

  it('renders a user message as plain text', () => {
    const message: UserMessage = { id: '1', role: 'user', text: 'Is water damage covered?' };
    fixture.componentRef.setInput('message', message);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Is water damage covered?');
    expect(fixture.nativeElement.querySelector('.message-bubble--user')).toBeTruthy();
  });

  it('renders a grounded answer with its citations', () => {
    const message: AssistantMessage = {
      id: '2',
      role: 'assistant',
      kind: 'grounded',
      text: 'Water damage is covered.',
      sources: [{ documentId: 'd1', document: 'Home Policy', version: '1.0', page: 1, section: null, chunkId: 'c1' }],
      traceId: 'trace-1',
      piiDetected: false,
    };
    fixture.componentRef.setInput('message', message);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Water damage is covered.');
    expect(fixture.nativeElement.querySelector('app-citation-list')).toBeTruthy();
  });

  it('renders the insufficient-evidence state for a no-answer message, never a bare message', () => {
    const message: AssistantMessage = {
      id: '3',
      role: 'assistant',
      kind: 'no-answer',
      text: 'I do not have sufficient information in the available documentation to answer reliably.',
      sources: [],
      traceId: 'trace-2',
      piiDetected: false,
    };
    fixture.componentRef.setInput('message', message);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Insufficient evidence');
    expect(fixture.nativeElement.textContent).toContain('specific policy, procedure or document');
  });

  it('renders the security banner for a blocked message, never exposing implementation detail', () => {
    const message: AssistantMessage = {
      id: '4',
      role: 'assistant',
      kind: 'blocked',
      text: 'This question could not be processed…',
      sources: [],
      traceId: 'trace-3',
      piiDetected: false,
    };
    fixture.componentRef.setInput('message', message);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-security-banner')).toBeTruthy();
    expect(fixture.nativeElement.textContent).not.toContain('regex');
    expect(fixture.nativeElement.textContent).not.toContain('ignore_instructions');
  });

  it('renders a generic error state with technical details, never the raw message alone', () => {
    const message: AssistantMessage = {
      id: '5',
      role: 'assistant',
      kind: 'error',
      text: 'The upstream AI provider rejected the request.',
      sources: [],
      traceId: 'trace-4',
      piiDetected: false,
      errorCode: 'OPENAI_CHAT_COMPLETION_REJECTED',
    };
    fixture.componentRef.setInput('message', message);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Something went wrong');
    expect(fixture.nativeElement.querySelector('app-technical-details')).toBeTruthy();
  });

  it('shows a discreet PII notice without altering the answer text', () => {
    const message: AssistantMessage = {
      id: '6',
      role: 'assistant',
      kind: 'grounded',
      text: 'Contact agent@example.com for details.',
      sources: [],
      traceId: 'trace-5',
      piiDetected: true,
    };
    fixture.componentRef.setInput('message', message);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Sensitive information detected');
    expect(fixture.nativeElement.textContent).toContain('Contact agent@example.com for details.');
  });
});

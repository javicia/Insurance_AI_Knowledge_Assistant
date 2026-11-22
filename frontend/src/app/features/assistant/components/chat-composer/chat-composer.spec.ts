import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { ChatComposer } from './chat-composer';

describe('ChatComposer', () => {
  let fixture: ComponentFixture<ChatComposer>;
  let component: ChatComposer;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatComposer],
      providers: [provideNoopAnimations()],
    }).compileComponents();
    fixture = TestBed.createComponent(ChatComposer);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  function textarea(): HTMLTextAreaElement {
    return fixture.nativeElement.querySelector('textarea');
  }

  it('emits the trimmed question on Enter and clears the input', () => {
    const emitted: string[] = [];
    component.send.subscribe((value) => emitted.push(value));

    textarea().value = 'Is water damage covered?';
    textarea().dispatchEvent(new Event('input'));
    fixture.detectChanges();

    textarea().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();

    expect(emitted).toEqual(['Is water damage covered?']);
    expect(textarea().value).toBe('');
  });

  it('does not send on Shift+Enter', () => {
    const emitted: string[] = [];
    component.send.subscribe((value) => emitted.push(value));

    textarea().value = 'question';
    textarea().dispatchEvent(new Event('input'));
    textarea().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true }));

    expect(emitted).toEqual([]);
  });

  it('does not send a blank question', () => {
    const emitted: string[] = [];
    component.send.subscribe((value) => emitted.push(value));

    textarea().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));

    expect(emitted).toEqual([]);
  });

  it('disables the send button while disabled=true even with text present', () => {
    fixture.componentRef.setInput('disabled', true);
    textarea().value = 'question';
    textarea().dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.chat-composer__send');
    expect(button.disabled).toBe(true);
  });
});

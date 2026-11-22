import { ComponentFixture, TestBed } from '@angular/core/testing';

import { StatusBadge } from './status-badge';

describe('StatusBadge', () => {
  let fixture: ComponentFixture<StatusBadge>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [StatusBadge] }).compileComponents();
    fixture = TestBed.createComponent(StatusBadge);
  });

  it('renders the given label', () => {
    fixture.componentRef.setInput('label', 'EMBEDDED');
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent?.trim()).toBe('EMBEDDED');
  });

  it('applies the requested variant class', () => {
    fixture.componentRef.setInput('label', 'FAILED');
    fixture.componentRef.setInput('variant', 'danger');
    fixture.detectChanges();

    const badge = fixture.nativeElement.querySelector('.status-badge');
    expect(badge.classList).toContain('status-badge--danger');
  });

  it('defaults to the neutral variant', () => {
    fixture.componentRef.setInput('label', 'UPLOADED');
    fixture.detectChanges();

    const badge = fixture.nativeElement.querySelector('.status-badge');
    expect(badge.classList).toContain('status-badge--neutral');
  });
});

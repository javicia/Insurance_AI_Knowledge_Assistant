import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CitationList } from './citation-list';
import type { SourceReference } from '../../../../core/models/chat.model';

describe('CitationList', () => {
  let fixture: ComponentFixture<CitationList>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [CitationList] }).compileComponents();
    fixture = TestBed.createComponent(CitationList);
  });

  it('renders one citation card per source', () => {
    const sources: SourceReference[] = [
      { documentId: 'd1', document: 'Home Policy', version: '1.0', page: 12, section: '7.2 Water Damage', chunkId: 'c1' },
      { documentId: 'd2', document: 'Claims Procedure', version: '2.0', page: null, section: null, chunkId: 'c2' },
    ];
    fixture.componentRef.setInput('sources', sources);
    fixture.detectChanges();

    const cards = fixture.nativeElement.querySelectorAll('app-citation-card');
    expect(cards.length).toBe(2);
  });

  it('renders nothing when there are no sources', () => {
    fixture.componentRef.setInput('sources', []);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.citation-list')).toBeNull();
  });
});

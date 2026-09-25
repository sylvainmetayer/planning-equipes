import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { PastilleFerie } from './pastille-ferie';

function render(libelle: string, compact: boolean): HTMLElement {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  const fixture = TestBed.createComponent(PastilleFerie);
  fixture.componentRef.setInput('libelle', libelle);
  fixture.componentRef.setInput('compact', compact);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('PastilleFerie', () => {
  it('shows the holiday by name, and says what it changes in words, not only a colour', () => {
    const pastille = render('Assomption', false).querySelector('.pastille-ferie')!;

    expect(pastille.textContent).toContain('Assomption');
    expect(pastille.textContent).toContain('les mineurs ne peuvent pas y travailler (L3164-6)');
    // Reachable from the keyboard, so its tooltip is too.
    expect(pastille.getAttribute('tabindex')).toBe('0');
  });

  it('shows « Férié » where a column is narrow, the name staying in the text read aloud', () => {
    const pastille = render('Fête nationale', true).querySelector('.pastille-ferie')!;

    expect(pastille.firstChild?.textContent).toBe('Férié');
    expect(pastille.textContent).toContain('Fête nationale — jour férié');
  });
});

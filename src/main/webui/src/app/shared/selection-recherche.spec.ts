// Header: this picker replaces a `mat-select` over 153 animateurs, so it is
// the entry point of the timeline and what-if pages. The logic cases below were
// there first; the rendering ones were added because the hint line, the chips
// and the removal buttons only exist in the template — and because that is
// where the accessible name of the field lives.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { OptionSelection, SelectionRecherche } from './selection-recherche';

/** Structural view of the component's template-only API. */
interface PickerApi {
  saisie: { set(value: string): void };
  optionsFiltrees(): OptionSelection[];
  resume(): string;
  choisir(id: string): void;
  retirer(id: string): void;
  onSaisieSimple(valeur: string): void;
}

const ANIMATEURS: OptionSelection[] = [
  { id: 'A1', label: 'Émile Zola' },
  { id: 'A2', label: 'Amélie Nothomb' },
  { id: 'A3', label: 'Marcel Proust' }
];

describe('SelectionRecherche', () => {
  let fixture: ComponentFixture<SelectionRecherche>;
  let picker: PickerApi;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    fixture = TestBed.createComponent(SelectionRecherche);
    fixture.componentRef.setInput('options', ANIMATEURS);
    picker = fixture.componentInstance as unknown as PickerApi;
  });

  it('filters on accent- and case-insensitive text', () => {
    picker.saisie.set('emile');
    expect(picker.optionsFiltrees().map((option) => option.id)).toEqual(['A1']);

    picker.saisie.set('NOTHOMB');
    expect(picker.optionsFiltrees().map((option) => option.id)).toEqual(['A2']);
  });

  it('replaces the value in single mode', () => {
    picker.choisir('A1');
    expect(fixture.componentInstance.valeurs()).toEqual(['A1']);

    picker.choisir('A3');
    expect(fixture.componentInstance.valeurs()).toEqual(['A3']);
  });

  it('accumulates and removes in multiple mode, never offering a pick twice', () => {
    fixture.componentRef.setInput('multiple', true);

    picker.choisir('A1');
    picker.choisir('A2');
    expect(fixture.componentInstance.valeurs()).toEqual(['A1', 'A2']);
    expect(picker.optionsFiltrees().map((option) => option.id)).toEqual(['A3']);

    picker.retirer('A1');
    expect(fixture.componentInstance.valeurs()).toEqual(['A2']);
  });

  it('clearing the field clears the selection, so the input never lies', () => {
    picker.choisir('A1');
    picker.onSaisieSimple('');
    expect(fixture.componentInstance.valeurs()).toEqual([]);
  });

  it('says how many entries the list holds, and how many the filter kept', () => {
    expect(picker.resume()).toBe('3 entrée(s) — tapez pour filtrer');

    picker.saisie.set('ol');
    expect(picker.resume()).toBe('1 proposition(s) sur 3');
  });

  it('counts the picks rather than the propositions in multiple mode', () => {
    fixture.componentRef.setInput('multiple', true);
    picker.choisir('A1');

    expect(picker.resume()).toBe('1 sélectionné(s) sur 3');
  });

  it('caps the proposition list, so a 150-entry referential never renders whole', () => {
    fixture.componentRef.setInput(
      'options',
      Array.from({ length: 153 }, (_unused, index) => ({ id: `A${index}`, label: `Animateur ${index}` }))
    );

    expect(picker.optionsFiltrees()).toHaveLength(50);
  });

  it('renders the field with the label as its accessible name in multiple mode', async () => {
    fixture.componentRef.setInput('multiple', true);
    fixture.componentRef.setInput('label', 'Animateurs');
    await fixture.whenStable();

    const racine = fixture.nativeElement as HTMLElement;
    expect(racine.querySelector('mat-chip-grid')!.getAttribute('aria-label')).toBe('Animateurs');
    expect(racine.querySelector('mat-hint')!.textContent!.trim()).toBe('0 sélectionné(s) sur 3');
  });

  it('renders one removable chip per pick, each naming what it removes', async () => {
    fixture.componentRef.setInput('multiple', true);
    picker.choisir('A1');
    picker.choisir('A3');
    await fixture.whenStable();

    const racine = fixture.nativeElement as HTMLElement;
    const chips = Array.from(racine.querySelectorAll('mat-chip-row'));
    // The trailing text is the remove button's icon ligature.
    expect(chips.map((each) => each.textContent!.replace('cancel', '').trim())).toEqual([
      'Émile Zola',
      'Marcel Proust'
    ]);
    // "Retirer" alone, repeated, tells a screen-reader user nothing.
    expect(
      Array.from(racine.querySelectorAll('mat-chip-row button')).map((each) => each.getAttribute('aria-label'))
    ).toEqual(['Retirer Émile Zola', 'Retirer Marcel Proust']);
  });

  it('disables the input when the caller disables the picker', async () => {
    fixture.componentRef.setInput('disabled', true);
    fixture.componentRef.setInput('placeholder', 'Tapez un nom');
    await fixture.whenStable();

    const input = (fixture.nativeElement as HTMLElement).querySelector('input') as HTMLInputElement;
    expect(input.disabled).toBe(true);
    expect(input.placeholder).toBe('Tapez un nom');
  });

  it('keeps the previous pick when the typed text no longer matches it', () => {
    picker.choisir('A1');
    picker.onSaisieSimple('zzz');

    // CONSTATÉ, NON VOULU: in single mode only an *empty* field clears the
    // selection, so the field reads "zzz" while the component still answers
    // "Émile Zola" — the very thing the code comment above `onSaisieSimple`
    // says never happens. Left as is: clearing on every non-matching keystroke
    // is a UX arbitration, not an obvious fix. Reported separately.
    expect(fixture.componentInstance.valeurs()).toEqual(['A1']);
  });
});

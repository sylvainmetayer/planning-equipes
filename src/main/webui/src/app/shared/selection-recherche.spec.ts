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

  // The field must never show one name while the component answers another:
  // callers read the selection to export a planning, and the person on screen
  // is the one the user believes they picked.
  it('drops the pick as soon as the typed text stops naming it', () => {
    picker.choisir('A1');
    picker.onSaisieSimple('zzz');

    expect(fixture.componentInstance.valeurs()).toEqual([]);
  });

  it('keeps the pick while the text still names it', () => {
    picker.choisir('A1');
    // `choisir` writes the option's own label into the field; retyping it
    // character for character must not drop the selection.
    picker.onSaisieSimple('Émile Zola');

    expect(fixture.componentInstance.valeurs()).toEqual(['A1']);
  });

  // Same rule, selection coming from outside: `/animateur-timeline` reads its
  // animateur from the URL, so `choisir` never runs and the field used to show
  // its placeholder while a pick was live — and the first keystroke then
  // dropped a selection nobody could see.
  it('shows a selection it received from its caller', async () => {
    fixture.componentRef.setInput('valeurs', ['A1']);
    await fixture.whenStable();

    const input = (fixture.nativeElement as HTMLElement).querySelector('input') as HTMLInputElement;
    expect(input.value).toBe('Émile Zola');
  });

  it('waits for the options before writing anything in the field', async () => {
    fixture.componentRef.setInput('options', []);
    fixture.componentRef.setInput('valeurs', ['A1']);
    await fixture.whenStable();

    const input = (fixture.nativeElement as HTMLElement).querySelector('input') as HTMLInputElement;
    expect(input.value).toBe('');

    // The list arrives late — the field catches up instead of staying blank.
    fixture.componentRef.setInput('options', ANIMATEURS);
    await fixture.whenStable();
    expect(input.value).toBe('Émile Zola');
  });
});

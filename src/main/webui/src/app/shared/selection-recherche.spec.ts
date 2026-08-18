import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { OptionSelection, SelectionRecherche } from './selection-recherche';

/** Structural view of the component's template-only API. */
interface PickerApi {
  saisie: { set(value: string): void };
  optionsFiltrees(): OptionSelection[];
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
});

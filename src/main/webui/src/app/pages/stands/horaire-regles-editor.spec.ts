// The shared rule editor, mounted inside a host form the way both dialogs
// mount it: what is checked is the bridge — its controls register with the
// host's form under the given prefix — and that every edit comes back as a
// new array rather than a mutation of the input.

import { Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule, NgForm } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HoraireDraft } from './stand-draft';
import { HoraireReglesEditor } from './horaire-regles-editor';

@Component({
  imports: [FormsModule, HoraireReglesEditor],
  template: `
    <form>
      <app-horaire-regles-editor [prefixe]="prefixe" [horaires]="horaires()" [effectifMax]="4"
                                 (horairesChange)="recu.push($event); horaires.set($event)" />
    </form>
  `
})
class Hote {
  prefixe = 'bulk';
  readonly horaires = signal<HoraireDraft[]>([]);
  readonly recu: HoraireDraft[][] = [];
}

function monter(): ComponentFixture<Hote> {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  return TestBed.createComponent(Hote);
}

function nomsEnregistres(fixture: ComponentFixture<Hote>): string[] {
  return Object.keys(fixture.debugElement.query(By.directive(NgForm)).injector.get(NgForm).controls);
}

function bouton(fixture: ComponentFixture<Hote>, libelle: string): HTMLButtonElement {
  return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((each) =>
    each.textContent!.includes(libelle)
  )!;
}

describe('HoraireReglesEditor', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => vi.restoreAllMocks());

  it('registers its controls with the host form, under the prefix, and never mutates the input', async () => {
    const fixture = monter();
    await fixture.whenStable();
    const avant = fixture.componentInstance.horaires();

    bouton(fixture, "Ajouter une règle d'horaire").click();
    await fixture.whenStable();

    expect(nomsEnregistres(fixture)).toContain('bulkfenetresLigne0');
    expect(fixture.componentInstance.recu).toHaveLength(1);
    expect(avant).toHaveLength(0);
    expect(fixture.componentInstance.horaires()).toHaveLength(1);
  });

  it('turns the typed line into windows, and refuses one above the given capacity', async () => {
    const fixture = monter();
    await fixture.whenStable();
    bouton(fixture, "Ajouter une règle d'horaire").click();
    await fixture.whenStable();
    const ligne = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[name="bulkfenetresLigne0"]')!;

    ligne.value = '10:00-12:00@2, 14:00-';
    ligne.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(fixture.componentInstance.horaires()[0].fenetres).toEqual([
      { heureDebut: '10:00', heureFin: '12:00', effectif: 2 },
      { heureDebut: '14:00', heureFin: null, effectif: null }
    ]);
    expect((fixture.nativeElement as HTMLElement).querySelector('.field-error')).toBeNull();

    ligne.value = '10:00-12:00@9';
    ligne.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('.field-error')!.textContent).toContain('(9)');
  });
});

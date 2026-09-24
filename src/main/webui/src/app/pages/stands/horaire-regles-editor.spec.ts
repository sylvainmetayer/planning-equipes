// The shared rule editor, mounted inside a host form the way both dialogs
// mount it: what is checked is the bridge — its controls register with the
// host's form under the given prefix — and that every edit comes back as a
// new array rather than a mutation of the input.

import { Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule, NgForm } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { horaireVide } from '../../core/horaire-stand';
import { HoraireDraft } from './stand-draft';
import { HoraireReglesEditor } from './horaire-regles-editor';

@Component({
  imports: [FormsModule, HoraireReglesEditor],
  template: `
    <form>
      <app-horaire-regles-editor
        [prefixe]="prefixe"
        [horaires]="horaires()"
        [effectifMax]="4"
        [jours]="jours"
        (horairesChange)="recu.push($event); horaires.set($event)"
      />
    </form>
  `,
})
class Hote {
  prefixe = 'bulk';
  /** Two days of the edition, so rules can be judged on a calendar. */
  readonly jours = [
    { date: '2026-07-08', fin: '20:00' },
    { date: '2026-07-09', fin: '20:00' },
  ];
  readonly horaires = signal<HoraireDraft[]>([]);
  readonly recu: HoraireDraft[][] = [];
}

function monter(): ComponentFixture<Hote> {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  return TestBed.createComponent(Hote);
}

function nomsEnregistres(fixture: ComponentFixture<Hote>): string[] {
  return Object.keys(
    fixture.debugElement.query(By.directive(NgForm)).injector.get(NgForm).controls,
  );
}

function bouton(fixture: ComponentFixture<Hote>, libelle: string): HTMLButtonElement {
  return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
    (each) => each.textContent!.includes(libelle),
  )!;
}

/** A plain rule, the shape the form starts a stand on. */
function regle(): HoraireDraft {
  return {
    ...horaireVide(),
    fenetres: [{ heureDebut: '10:00', heureFin: '12:00', effectif: null }],
  };
}

/** The rendered selects, in template order: mode then days when unfolded, none when folded. */
function selects(fixture: ComponentFixture<Hote>): HTMLElement[] {
  return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('mat-select'));
}

/** Picks an option of a `mat-select` the way a user does: open, then click. */
async function choisir(
  fixture: ComponentFixture<Hote>,
  index: number,
  libelle: string,
): Promise<void> {
  (selects(fixture)[index].querySelector('.mat-mdc-select-trigger') as HTMLElement).click();
  await fixture.whenStable();
  const option = Array.from(document.querySelectorAll('mat-option')).find(
    (each) => each.textContent!.trim() === libelle,
  );
  expect(option, `option « ${libelle} » absente`).toBeDefined();
  (option as HTMLElement).click();
  await fixture.whenStable();
}

describe('HoraireReglesEditor', () => {
  let erreursConsole: unknown[][];

  beforeEach(() => {
    erreursConsole = [];
    vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) =>
      erreursConsole.push(args),
    );
  });
  afterEach(() => vi.restoreAllMocks());

  /** Every control the editor renders, and what the host form registered. */
  function controles(fixture: ComponentFixture<Hote>): Element[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        'input[matInput], mat-select, mat-checkbox',
      ),
    );
  }

  it('registers its controls with the host form, under the prefix, and never mutates the input', async () => {
    const fixture = monter();
    await fixture.whenStable();
    const before = fixture.componentInstance.horaires();

    bouton(fixture, "Ajouter une règle d'horaire").click();
    await fixture.whenStable();

    expect(nomsEnregistres(fixture)).toContain('bulkfenetresLigne0');
    // An unnamed ngModel throws NG01352 and registers nothing: the count is
    // what catches it, and this spec is the one that watches the bridge.
    expect(nomsEnregistres(fixture)).toHaveLength(controles(fixture).length);
    expect(erreursConsole.filter((args) => JSON.stringify(args).includes('NG01352'))).toEqual([]);
    expect(fixture.componentInstance.recu).toHaveLength(1);
    expect(before).toHaveLength(0);
    expect(fixture.componentInstance.horaires()).toHaveLength(1);
  });

  // Une règle qui cesse d'être un cas particulier se repliait sous le curseur :
  // le sélecteur que l'on venait d'utiliser disparaissait.
  it('keeps a card unfolded once its selectors have been used', async () => {
    const fixture = monter();
    fixture.componentInstance.horaires.set([{ ...regle(), jours: 'DATES', dates: ['2026-07-14'] }]);
    await fixture.whenStable();
    expect(nomsEnregistres(fixture)).toContain('bulkhoraireJours0');

    // Back to « every day »: the rule stops being a special case, and the
    // selector just used must not vanish from under the cursor.
    await choisir(fixture, 1, 'Tous les jours');

    expect(fixture.componentInstance.horaires()[0].jours).toBe('TOUS');
    expect(nomsEnregistres(fixture)).toContain('bulkhoraireJours0');
    expect(bouton(fixture, 'Cas particulier').getAttribute('aria-expanded')).toBe('true');
  });

  it('turns the typed line into windows, and refuses one above the given capacity', async () => {
    const fixture = monter();
    await fixture.whenStable();
    bouton(fixture, "Ajouter une règle d'horaire").click();
    await fixture.whenStable();
    const ligne = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      'input[name="bulkfenetresLigne0"]',
    )!;

    ligne.value = '10:00-12:00@2, 14:00-';
    ligne.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(fixture.componentInstance.horaires()[0].fenetres).toEqual([
      { heureDebut: '10:00', heureFin: '12:00', effectif: 2 },
      { heureDebut: '14:00', heureFin: null, effectif: null },
    ]);
    expect((fixture.nativeElement as HTMLElement).querySelector('.field-error')).toBeNull();

    ligne.value = '10:00-12:00@9';
    ligne.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('.field-error')!.textContent,
    ).toContain('(9)');
  });

  it('warns under the later of two overlapping rules without blocking anything', async () => {
    const fixture = monter();
    fixture.componentInstance.horaires.set([
      { ...horaireVide(), fenetres: [{ heureDebut: '14:00', heureFin: '20:00', effectif: 4 }] },
      { ...horaireVide(), fenetres: [{ heureDebut: '14:00', heureFin: '20:00', effectif: 2 }] },
    ]);
    await fixture.whenStable();

    const cartes = (fixture.nativeElement as HTMLElement).querySelectorAll('.horaire-carte');
    expect(cartes[0].querySelector('.horaire-avertissement')).toBeNull();
    expect(cartes[1].querySelector('.horaire-avertissement')!.textContent).toContain(
      "l'effectif retenu est 4 (le plus haut), pas 2",
    );
    // A warning, not an error: the card is not flagged and nothing says « refusé ».
    expect(cartes[1].classList).not.toContain('horaire-carte-erreur');
    expect((fixture.nativeElement as HTMLElement).querySelector('.field-error')).toBeNull();
  });

  it('shows the error alone under a rule whose entry is in error', async () => {
    const fixture = monter();
    fixture.componentInstance.horaires.set([
      { ...horaireVide(), fenetres: [{ heureDebut: '14:00', heureFin: '20:00', effectif: 4 }] },
      { ...horaireVide(), fenetres: [{ heureDebut: '14:00', heureFin: '20:00', effectif: 9 }] },
    ]);
    await fixture.whenStable();

    const cartes = (fixture.nativeElement as HTMLElement).querySelectorAll('.horaire-carte');
    expect(cartes[1].classList).toContain('horaire-carte-erreur');
    expect(cartes[1].querySelector('.field-error')!.textContent).toContain('(9)');
    expect(cartes[1].querySelector('.horaire-avertissement')).toBeNull();
  });
});

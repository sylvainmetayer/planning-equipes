// The comparison rendering itself: that the banner sums up the lines the
// filters keep, and says so, rather than the whole day under a filtered table.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { Animateur, PlanningEvenement, PosteAffectation, Stand } from '../../core/models';
import { ValidationsStore } from '../../core/validations.store';
import { ComparaisonView } from './comparaison-vue';
import { JourEvenement } from './journee';

const SAMEDI = { id: 1, jour: 1, date: '2026-07-12', heureDebut: '10:00', heureFin: '12:00' };
const SAMEDI_SUIVANT = {
  id: 2,
  jour: 8,
  date: '2026-07-19',
  heureDebut: '10:00',
  heureFin: '12:00',
};

const jeux = { id: 'S1', nom: 'Jeux' } as Stand;
const buvette = { id: 'S2', nom: 'Buvette' } as Stand;
const alice = { id: 'a1', prenom: 'Alice', nom: 'Martin' } as Animateur;

function planning(): PlanningEvenement {
  const postes: PosteAffectation[] = [
    { id: 'p1', creneau: SAMEDI, stand: jeux, animateur: alice },
    { id: 'p2', creneau: SAMEDI, stand: buvette, animateur: null },
    { id: 'p3', creneau: SAMEDI, stand: buvette, animateur: null },
    { id: 'p4', creneau: SAMEDI_SUIVANT, stand: jeux, animateur: alice },
  ];
  return { animateurs: [alice], postes, score: null };
}

const JOUR_A: JourEvenement = { jour: 1, date: '2026-07-12', key: '2026-07-12', title: 'Sam. 12' };
const JOUR_B: JourEvenement = { jour: 8, date: '2026-07-19', key: '2026-07-19', title: 'Sam. 19' };

describe('ComparaisonView', () => {
  let fixture: ComponentFixture<ComparaisonView>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ValidationsStore, useValue: { acceptedDays: () => new Set<string>() } },
      ],
    });
    fixture = TestBed.createComponent(ComparaisonView);
    fixture.componentRef.setInput('planning', planning());
    fixture.componentRef.setInput('jourA', JOUR_A);
    fixture.componentRef.setInput('jourB', JOUR_B);
  });

  /** The banner's cell of one figure (0 = seats to fill) on one side (0 = A, 1 = B). */
  function cellule(ligne: number, colonne: number): string {
    const lignes = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '.journee-synthese tbody tr',
    );
    return lignes[ligne].querySelectorAll('td')[colonne].textContent?.trim() ?? '';
  }

  function pageText(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('sums up the whole of both days when no filter is set', async () => {
    await fixture.whenStable();

    expect(cellule(0, 0)).toBe('3');
    expect(cellule(0, 1)).toBe('1');
    expect(pageText()).not.toContain('filtré');
    // The cells render through their templates: the stand closed on B says so.
    expect(pageText()).toContain('fermé ce jour-là');
    expect(pageText()).toContain('1 / 1 sièges pourvus');
  });

  it('sums up the lines the filters keep, and says the banner is filtered', async () => {
    fixture.componentRef.setInput('stand', 'S1');
    await fixture.whenStable();

    expect(cellule(0, 0)).toBe('1');
    expect(cellule(0, 1)).toBe('1');
    expect(pageText()).toContain('filtré');
  });

  it('follows « seulement les différences » as well', async () => {
    fixture.componentRef.setInput('seulementEcarts', true);
    await fixture.whenStable();

    // Jeux is alike on both days: only Buvette is left, open on A alone.
    expect(cellule(0, 0)).toBe('2');
    expect(cellule(0, 1)).toBe('0');
    expect(pageText()).toContain('filtré');
  });

  it('renders the rail cells through their template too', async () => {
    fixture.componentRef.setInput('view', 'rail');
    await fixture.whenStable();

    expect(pageText()).toContain('Alice Martin');
    expect(pageText()).toContain('10:00–12:00');
  });
});

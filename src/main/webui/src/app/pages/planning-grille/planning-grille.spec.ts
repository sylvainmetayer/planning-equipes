// The grid the Planning page's « Par stand » and « Par personne » axes share:
// its frozen first column and its templates, its one tab stop and its arrows,
// the cell it reports opened, and the sort it asks of its caller.

import { Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { NO_SORT, SortState } from '../../core/view-query-params';
import {
  CaseActivee,
  GrilleCase,
  GrilleEntete,
  JourGrille,
  LigneGrille,
  PlanningGrille,
} from './planning-grille';

const JOURS: JourGrille[] = [
  { key: 'd1', label: 'J1', initiale: 'S', titre: 'Jour 1', weekEnd: true, debutSemaine: false },
  { key: 'd2', label: 'J2', initiale: 'D', titre: 'Jour 2', weekEnd: true, debutSemaine: false },
];

const LIGNES: LigneGrille[] = [
  {
    id: 'l1',
    cases: [
      { classe: 'plein', libelle: 'Tir J1', active: true },
      { classe: 'ferme', libelle: 'Tir J2', active: false },
    ],
    synthese: { total: { texte: '3' } },
  },
  {
    id: 'l2',
    cases: [
      { classe: 'plein', libelle: 'Dixit J1', active: true },
      { classe: 'plein', libelle: 'Dixit J2', active: true },
    ],
    synthese: { total: { texte: '5' } },
  },
];

@Component({
  imports: [PlanningGrille, GrilleEntete, GrilleCase],
  template: `
    <app-planning-grille caption="Essai" header="Stand" [jours]="jours" [lignes]="lignes"
                         [syntheses]="[{ key: 'total', label: 'Total' }]" [triable]="true" [(tri)]="tri"
                         [jourMarque]="marque()" (caseActivee)="ouvertes.push($event)">
      <ng-template appGrilleEntete let-ligne>{{ ligne.id }}</ng-template>
      <ng-template appGrilleCase let-ligne let-index="index">{{ ligne.id }}-{{ index }}</ng-template>
    </app-planning-grille>
  `,
})
class Hote {
  readonly jours = JOURS;
  readonly lignes = LIGNES;
  readonly tri = signal<SortState>(NO_SORT);
  readonly marque = signal<string | null>(null);
  readonly ouvertes: CaseActivee<LigneGrille>[] = [];
}

async function monter() {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  const fixture = TestBed.createComponent(Hote);
  await fixture.whenStable();
  const racine = fixture.nativeElement as HTMLElement;
  const cellule = (ligne: number, colonne: number) =>
    racine.querySelector<HTMLElement>(`[data-ligne="${ligne}"][data-colonne="${colonne}"]`)!;
  return { fixture, racine, cellule, hote: fixture.componentInstance };
}

describe('PlanningGrille', () => {
  it('draws a line per row, a column per day, the summary at the right, through its templates', async () => {
    const { racine, cellule } = await monter();

    expect(
      Array.from(racine.querySelectorAll('tbody th')).map((th) => th.textContent!.trim()),
    ).toEqual(['l1', 'l2']);
    expect(cellule(1, 1).textContent!.trim()).toBe('l2-1');
    expect(cellule(0, 1).classList).toContain('ferme');
    expect(cellule(0, 0).getAttribute('aria-label')).toBe('Tir J1');
    expect(racine.querySelectorAll('td.planning-grille-synthese')[1].textContent!.trim()).toBe('5');
  });

  it('is one tab stop, moved by the arrows, and opens a cell on Enter — never a closed one', async () => {
    const { fixture, cellule, hote } = await monter();
    expect(cellule(0, 0).tabIndex).toBe(0);
    expect(cellule(1, 0).tabIndex).toBe(-1);

    cellule(0, 0).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    await fixture.whenStable();
    expect(cellule(1, 0).tabIndex).toBe(0);

    cellule(1, 0).dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    cellule(0, 1).click();
    expect(hote.ouvertes.map((ouverte) => [ouverte.ligne.id, ouverte.jour.key])).toEqual([
      ['l2', 'd1'],
    ]);
  });

  // The Planning page gives the focus back by this key once a gesture has redrawn the grid.
  it('keys every cell that opens a seat by its line and its day, and no other', async () => {
    const { cellule } = await monter();
    expect(cellule(1, 1).dataset['siegeCle']).toBe('grille|l2|d2');
    expect(cellule(0, 0).dataset['siegeCle']).toBe('grille|l1|d1');
    expect(cellule(0, 1).hasAttribute('data-siege-cle')).toBe(false);
  });

  it('asks its caller for a sort, ascending, descending, then none', async () => {
    const { fixture, racine, hote } = await monter();
    const entete = () => racine.querySelectorAll<HTMLButtonElement>('.planning-grille-tri')[1];

    entete().click();
    await fixture.whenStable();
    expect(hote.tri()).toEqual({ active: 'total', direction: 'asc' });
    expect(entete().closest('th')!.getAttribute('aria-sort')).toBe('ascending');

    entete().click();
    entete().click();
    expect(hote.tri()).toEqual(NO_SORT);
  });

  it('marks the day the page is on, and puts the tab stop in its column', async () => {
    const { fixture, racine, cellule, hote } = await monter();
    hote.marque.set('d2');
    await fixture.whenStable();

    expect(racine.querySelector('.planning-grille-marque')!.getAttribute('aria-current')).toBe(
      'date',
    );
    expect(cellule(0, 1).tabIndex).toBe(0);
  });
});

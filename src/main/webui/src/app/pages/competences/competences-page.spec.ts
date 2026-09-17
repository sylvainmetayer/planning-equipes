// Rendering tests of the competences grid: the moves live in
// `grille-competences.ts` and are tested there; what is checked here is the
// wiring — one button per cell, a key and a click reaching the cells, the
// save sending only what changed with its stamps, a stale row turned into the
// reload-or-overwrite choice, the lock while a solve runs, and the view state
// read from the URL.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmData, ConfirmService } from '../../shared/confirm-dialog';
import {
  Animateur,
  RapportSaisieCompetences,
  SaisieAnimateurCompetences,
  TypologieItem,
} from '../../core/models';
import { CompetencesPage } from './competences-page';

const TYPOLOGIES: TypologieItem[] = [
  { id: 'jeux', label: 'Jeux de société', ninja: false },
  { id: 'ateliers', label: 'Ateliers', ninja: false },
  { id: 'ninja', label: 'Ninja', ninja: true },
];

function roster(): Animateur[] {
  return [
    {
      id: 'A1',
      prenom: 'Alice',
      nom: 'Martin',
      dateNaissance: '1990-01-01',
      manager: false,
      competences: { jeux: 'REFERENT' },
      souhaits: ['ateliers'],
      joursIndisponibles: [],
      modifieLe: '2026-09-06T10:00:00Z',
    },
    {
      id: 'B2',
      prenom: 'Bruno',
      nom: 'Lefèvre',
      dateNaissance: '1992-02-02',
      manager: false,
      competences: {},
      souhaits: [],
      joursIndisponibles: [],
      modifieLe: '2026-09-06T11:00:00Z',
    },
  ];
}

function mount(
  options: {
    query?: Record<string, string>;
    editingLocked?: boolean;
    rapport?: RapportSaisieCompetences;
    choixConflit?: boolean | null;
    confirme?: boolean;
  } = {},
) {
  const animateurs = signal(roster());
  const store = {
    animateurs,
    typologies: signal(TYPOLOGIES),
    reload: vi.fn(async () => undefined),
  };
  const saveCompetencesGrid = vi.fn(
    async (_animateurs: SaisieAnimateurCompetences[]): Promise<RapportSaisieCompetences> =>
      options.rapport ?? {
        animateurs: [
          {
            animateurId: 'B2',
            resultat: 'WRITTEN',
            message: null,
            modifieLe: '2026-09-07T00:00:00Z',
          },
        ],
      },
  );
  const ask = vi.fn(async (_data: ConfirmData) => options.confirme ?? true);
  const askThreeWay = vi.fn(async (_data: ConfirmData) => options.choixConflit ?? null);
  const notify = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: AnimateursApi, useValue: { saveCompetencesGrid } },
      { provide: ReferenceDataStore, useValue: store },
      { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
      {
        provide: SolverJobService,
        useValue: { editingLocked: signal(options.editingLocked ?? false) },
      },
      { provide: ConfirmService, useValue: { ask, askThreeWay } },
      { provide: NotificationService, useValue: { notify } },
      { provide: Location, useValue: { path: () => '/competences', replaceState: vi.fn() } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: convertToParamMap(options.query ?? {}) } },
      },
    ],
  });
  const fixture = TestBed.createComponent(CompetencesPage);
  return { fixture, store, animateurs, saveCompetencesGrid, ask, askThreeWay, notify };
}

function root(fixture: ComponentFixture<CompetencesPage>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function cellule(
  fixture: ComponentFixture<CompetencesPage>,
  animateurId: string,
  typologieId: string,
): HTMLButtonElement {
  const bouton = root(fixture).querySelector<HTMLButtonElement>(
    `[data-cellule="${animateurId}#${typologieId}"]`,
  );
  expect(bouton, `case ${animateurId}#${typologieId}`).not.toBeNull();
  return bouton!;
}

function touche(element: HTMLElement, key: string): void {
  element.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
}

function combinaison(element: HTMLElement, key: string, modificateurs: KeyboardEventInit): void {
  element.dispatchEvent(
    new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...modificateurs }),
  );
}

/** « Reprendre la ligne du dessus », on the row of that animateur. */
function boutonLigne(
  fixture: ComponentFixture<CompetencesPage>,
  animateurId: string,
): HTMLButtonElement {
  const trouve = cellule(fixture, animateurId, 'jeux')
    .closest('tr')!
    .querySelector<HTMLButtonElement>('.dupliquer-ligne');
  expect(trouve, `bouton « dupliquer » de ${animateurId}`).not.toBeNull();
  return trouve!;
}

/** « Appliquer à toute la colonne », on the header of that typologie. */
function boutonColonne(
  fixture: ComponentFixture<CompetencesPage>,
  libelle: string,
): HTMLButtonElement {
  const entete = Array.from(root(fixture).querySelectorAll('th.colonne-typologie')).find((each) =>
    each.textContent?.includes(libelle),
  );
  expect(entete, `colonne « ${libelle} »`).toBeDefined();
  const trouve = entete!.querySelector<HTMLButtonElement>('.appliquer-colonne');
  expect(trouve, `bouton « appliquer » de ${libelle}`).not.toBeNull();
  return trouve!;
}

function bouton(fixture: ComponentFixture<CompetencesPage>, libelle: string): HTMLButtonElement {
  const trouve = Array.from(root(fixture).querySelectorAll('button')).find((each) =>
    each.textContent?.includes(libelle),
  );
  expect(trouve, `bouton « ${libelle} »`).toBeDefined();
  return trouve!;
}

describe('CompetencesPage', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => vi.restoreAllMocks());

  it('renders one button per animateur and typologie, the wish and the ninja column marked', async () => {
    const { fixture, store } = mount();
    await fixture.whenStable();

    expect(store.reload).toHaveBeenCalledWith(['animateurs', 'typologies']);
    expect(root(fixture).querySelectorAll('.cellule-competence')).toHaveLength(6);
    expect(cellule(fixture, 'A1', 'jeux').textContent!.trim()).toBe('R');
    expect(cellule(fixture, 'A1', 'jeux').getAttribute('data-niveau')).toBe('REFERENT');
    expect(cellule(fixture, 'B2', 'jeux').textContent!.trim()).toBe('');
    expect(cellule(fixture, 'A1', 'ateliers').classList.contains('cellule-souhait')).toBe(true);
    expect(cellule(fixture, 'A1', 'ateliers').getAttribute('aria-label')).toContain('souhaitée');
    expect(cellule(fixture, 'B2', 'ateliers').classList.contains('cellule-souhait')).toBe(false);
    expect(cellule(fixture, 'A1', 'ninja').closest('td')!.classList.contains('colonne-ninja')).toBe(
      true,
    );
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);
  });

  /*
   * The grid scrolls in a box of its own, which is the whole reason its header
   * can stay put: `position: sticky` resolves against the nearest scrolling
   * ancestor, and `.table-wrapper` alone only ever scrolls sideways. Rendered
   * without CSS, this suite cannot judge what that produces —
   * `e2e/grilles-collantes.spec.ts` measures it in a browser — but it can hold
   * the class that turns it on, which a template edit drops without anything
   * else noticing.
   */
  it('wraps the grid in the scrolling box its sticky header needs', async () => {
    const { fixture } = mount();
    await fixture.whenStable();

    const grille = root(fixture).querySelector('.competences-grille');
    expect(grille).not.toBeNull();
    expect(grille!.closest('.table-wrapper.grille-defilement')).not.toBeNull();
  });

  it('sets the level from the keys 0-3, cycles on a click, and marks the cell and its row', async () => {
    const { fixture } = mount();
    await fixture.whenStable();

    touche(cellule(fixture, 'B2', 'jeux'), '3');
    await fixture.whenStable();
    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('REFERENT');
    expect(cellule(fixture, 'B2', 'jeux').classList.contains('cellule-modifiee')).toBe(true);
    expect(cellule(fixture, 'B2', 'jeux').closest('tr')!.classList.contains('ligne-modifiee')).toBe(
      true,
    );
    expect(bouton(fixture, 'Enregistrer').textContent).toContain('(1)');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(false);

    touche(cellule(fixture, 'A1', 'jeux'), '0');
    await fixture.whenStable();
    expect(cellule(fixture, 'A1', 'jeux').getAttribute('data-niveau')).toBe('');
    expect(bouton(fixture, 'Enregistrer').textContent).toContain('(2)');

    cellule(fixture, 'B2', 'ateliers').click();
    await fixture.whenStable();
    expect(cellule(fixture, 'B2', 'ateliers').getAttribute('data-niveau')).toBe('DEBUTANT');
    cellule(fixture, 'B2', 'ateliers').click();
    await fixture.whenStable();
    expect(cellule(fixture, 'B2', 'ateliers').getAttribute('data-niveau')).toBe('AUTONOME');

    bouton(fixture, 'Annuler les modifications').click();
    await fixture.whenStable();
    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('');
    expect(cellule(fixture, 'A1', 'jeux').getAttribute('data-niveau')).toBe('REFERENT');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);
  });

  it('moves the focus with Enter and the arrows, and carries one tabindex in the grid', async () => {
    const { fixture } = mount();
    await fixture.whenStable();

    expect(cellule(fixture, 'A1', 'jeux').getAttribute('tabindex')).toBe('0');
    expect(cellule(fixture, 'B2', 'jeux').getAttribute('tabindex')).toBe('-1');

    cellule(fixture, 'A1', 'jeux').focus();
    touche(cellule(fixture, 'A1', 'jeux'), 'Enter');
    expect(document.activeElement).toBe(cellule(fixture, 'B2', 'jeux'));
    touche(cellule(fixture, 'B2', 'jeux'), 'ArrowRight');
    expect(document.activeElement).toBe(cellule(fixture, 'B2', 'ateliers'));
    await fixture.whenStable();
    expect(cellule(fixture, 'B2', 'ateliers').getAttribute('tabindex')).toBe('0');
    expect(cellule(fixture, 'A1', 'jeux').getAttribute('tabindex')).toBe('-1');
  });

  it('sends only the modified animateurs, each with their whole map and the stamp read, then reloads', async () => {
    const { fixture, saveCompetencesGrid, store, notify } = mount();
    await fixture.whenStable();
    store.reload.mockClear();

    touche(cellule(fixture, 'B2', 'jeux'), '2');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    expect(saveCompetencesGrid).toHaveBeenCalledWith([
      { animateurId: 'B2', modifieLe: '2026-09-06T11:00:00Z', competences: { jeux: 'AUTONOME' } },
    ]);
    expect(store.reload).toHaveBeenCalledOnce();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
  });

  it('turns a stale row into the reload-or-overwrite choice: « Écraser » resends it without its stamp', async () => {
    const stale: RapportSaisieCompetences = {
      animateurs: [
        {
          animateurId: 'A1',
          resultat: 'WRITTEN',
          message: null,
          modifieLe: '2026-09-07T00:00:00Z',
        },
        {
          animateurId: 'B2',
          resultat: 'STALE',
          message: 'Cette fiche a été modifiée par une autre session',
          modifieLe: '2026-09-06T12:00:00Z',
        },
      ],
    };
    const { fixture, saveCompetencesGrid, askThreeWay } = mount({
      rapport: stale,
      choixConflit: true,
    });
    await fixture.whenStable();
    saveCompetencesGrid.mockResolvedValueOnce(stale).mockResolvedValueOnce({
      animateurs: [
        {
          animateurId: 'B2',
          resultat: 'WRITTEN',
          message: null,
          modifieLe: '2026-09-07T00:01:00Z',
        },
      ],
    });

    touche(cellule(fixture, 'A1', 'ateliers'), '1');
    touche(cellule(fixture, 'B2', 'jeux'), '2');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    expect(askThreeWay).toHaveBeenCalledOnce();
    expect(askThreeWay.mock.calls[0][0]).toEqual(
      expect.objectContaining({ cancelLabel: 'Recharger', confirmLabel: 'Écraser quand même' }),
    );
    expect(saveCompetencesGrid).toHaveBeenCalledTimes(2);
    expect(saveCompetencesGrid.mock.calls[1][0]).toEqual([
      { animateurId: 'B2', modifieLe: null, competences: { jeux: 'AUTONOME' } },
    ]);
  });

  // Two rounds, two kinds of refusal at once. The overwrite report knows only
  // the rows it resent: rebuilt from it, the refused row would be reloaded from
  // the server and the typing lost without a word.
  it('keeps a refused row as typed through an overwrite of a stale row', async () => {
    const melange: RapportSaisieCompetences = {
      animateurs: [
        {
          animateurId: 'A1',
          resultat: 'REJECTED',
          message: 'Niveau inconnu',
          modifieLe: null,
        },
        {
          animateurId: 'B2',
          resultat: 'STALE',
          message: 'Cette fiche a été modifiée par une autre session',
          modifieLe: '2026-09-06T12:00:00Z',
        },
      ],
    };
    const { fixture, saveCompetencesGrid, store, animateurs } = mount({
      rapport: melange,
      choixConflit: true,
    });
    await fixture.whenStable();
    saveCompetencesGrid.mockResolvedValueOnce(melange).mockResolvedValueOnce({
      animateurs: [
        {
          animateurId: 'B2',
          resultat: 'WRITTEN',
          message: null,
          modifieLe: '2026-09-07T00:01:00Z',
        },
      ],
    });
    // The server's version of A1 is the one it always had: nothing was written.
    store.reload.mockImplementation(async () => {
      animateurs.set(roster());
    });

    touche(cellule(fixture, 'A1', 'ateliers'), '1');
    touche(cellule(fixture, 'B2', 'jeux'), '2');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    expect(saveCompetencesGrid).toHaveBeenCalledTimes(2);
    const refusee = cellule(fixture, 'A1', 'ateliers');
    expect(refusee.dataset['niveau']).toBe('DEBUTANT');
    expect(refusee.classList.contains('cellule-modifiee')).toBe(true);
  });

  it("on « Recharger », writes nothing more and takes the other session's version", async () => {
    const stale: RapportSaisieCompetences = {
      animateurs: [
        {
          animateurId: 'B2',
          resultat: 'STALE',
          message: 'modifiée',
          modifieLe: '2026-09-06T12:00:00Z',
        },
      ],
    };
    const { fixture, saveCompetencesGrid, store, notify, animateurs } = mount({
      rapport: stale,
      choixConflit: false,
    });
    await fixture.whenStable();
    store.reload.mockImplementation(async () => {
      animateurs.set(
        roster().map((each) =>
          each.id === 'B2' ? { ...each, competences: { jeux: 'DEBUTANT' } } : each,
        ),
      );
    });

    touche(cellule(fixture, 'B2', 'jeux'), '3');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    expect(saveCompetencesGrid).toHaveBeenCalledOnce();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'warning' }));
    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('DEBUTANT');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);
  });

  it('keeps a dismissed stale row as typed, unsent', async () => {
    const stale: RapportSaisieCompetences = {
      animateurs: [
        {
          animateurId: 'B2',
          resultat: 'STALE',
          message: 'modifiée',
          modifieLe: '2026-09-06T12:00:00Z',
        },
      ],
    };
    const { fixture, saveCompetencesGrid } = mount({ rapport: stale, choixConflit: null });
    await fixture.whenStable();

    touche(cellule(fixture, 'B2', 'jeux'), '3');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    expect(saveCompetencesGrid).toHaveBeenCalledOnce();
    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('REFERENT');
    expect(cellule(fixture, 'B2', 'jeux').classList.contains('cellule-modifiee')).toBe(true);
  });

  it('locks every cell and the save while a solve runs', async () => {
    const { fixture } = mount({ editingLocked: true });
    await fixture.whenStable();

    expect(cellule(fixture, 'B2', 'jeux').disabled).toBe(true);
    touche(cellule(fixture, 'B2', 'jeux'), '3');
    await fixture.whenStable();
    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);
  });

  it('reads the name filter and the chosen columns from the URL, an unknown typologie dropped', async () => {
    const { fixture } = mount({ query: { q: 'lefevre', typologies: 'ateliers,inconnue' } });
    await fixture.whenStable();

    expect(root(fixture).querySelectorAll('.cellule-competence')).toHaveLength(1);
    expect(cellule(fixture, 'B2', 'ateliers')).toBeDefined();
    expect(root(fixture).querySelectorAll('th.colonne-typologie')).toHaveLength(1);
  });

  // The PUT replaces a person's whole map, so a view narrowed to one column
  // must still send the levels of the columns it does not show — otherwise
  // filtering the screen would quietly erase what is off screen.
  it('sends every typologie of a modified row, even from a view filtered to one column', async () => {
    const { fixture, saveCompetencesGrid } = mount({ query: { typologies: 'ateliers' } });
    await fixture.whenStable();

    touche(cellule(fixture, 'A1', 'ateliers'), '2');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    expect(saveCompetencesGrid).toHaveBeenCalledWith([
      {
        animateurId: 'A1',
        modifieLe: '2026-09-06T10:00:00Z',
        // « jeux » is not a column of this view, and its level travels anyway.
        competences: { jeux: 'REFERENT', ateliers: 'AUTONOME' },
      },
    ]);
  });

  it('asks before leaving with unsaved cells, and lets a clean page go', async () => {
    const { fixture, ask } = mount({ confirme: false });
    await fixture.whenStable();
    expect(await fixture.componentInstance.canLeave()).toBe(true);
    expect(ask).not.toHaveBeenCalled();

    touche(cellule(fixture, 'B2', 'jeux'), '1');
    await fixture.whenStable();
    expect(await fixture.componentInstance.canLeave()).toBe(false);
    expect(ask).toHaveBeenCalledOnce();
  });

  it('brings the import card under the eye: the grid is as long as the roster', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const { fixture } = mount();
    await fixture.whenStable();
    expect(scrollIntoView).not.toHaveBeenCalled();

    bouton(fixture, 'Importer un CSV').click();
    await fixture.whenStable();

    expect(root(fixture).querySelector('.competences-import')).not.toBeNull();
    expect(scrollIntoView).toHaveBeenCalled();
    expect((scrollIntoView.mock.instances[0] as HTMLElement).classList).toContain(
      'competences-import',
    );
  });

  /* ------------------------- repetitive entry (#316) ------------------------- */

  it('takes the row above into this one, on the button and on Ctrl+D', async () => {
    const { fixture } = mount();
    await fixture.whenStable();

    // Alice is a référent on « jeux » and nothing else; Bruno holds nothing.
    boutonLigne(fixture, 'B2').click();
    await fixture.whenStable();

    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('REFERENT');
    expect(cellule(fixture, 'B2', 'jeux').classList.contains('cellule-modifiee')).toBe(true);
    expect(bouton(fixture, 'Enregistrer').textContent).toContain('(1)');

    // The same move from the keyboard, once the row is back to what it said.
    bouton(fixture, 'Annuler les modifications').click();
    await fixture.whenStable();
    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('');

    combinaison(cellule(fixture, 'B2', 'ateliers'), 'd', { ctrlKey: true });
    await fixture.whenStable();
    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('REFERENT');
  });

  it('has nothing to take on the first displayed row, and says so from the keyboard', async () => {
    const { fixture, notify } = mount();
    await fixture.whenStable();

    expect(boutonLigne(fixture, 'A1').disabled).toBe(true);
    expect(boutonLigne(fixture, 'B2').disabled).toBe(false);

    combinaison(cellule(fixture, 'A1', 'jeux'), 'd', { ctrlKey: true });
    await fixture.whenStable();

    expect(notify).toHaveBeenCalledWith(
      expect.objectContaining({ variant: 'warning', title: expect.stringContaining('au-dessus') }),
    );
    expect(cellule(fixture, 'A1', 'jeux').getAttribute('data-niveau')).toBe('REFERENT');
  });

  it('applies the active cell to its whole column, leaving the other columns alone', async () => {
    const { fixture } = mount();
    await fixture.whenStable();

    touche(cellule(fixture, 'B2', 'ateliers'), '1');
    cellule(fixture, 'B2', 'ateliers').focus();
    await fixture.whenStable();

    boutonColonne(fixture, 'Ateliers').click();
    await fixture.whenStable();

    expect(cellule(fixture, 'A1', 'ateliers').getAttribute('data-niveau')).toBe('DEBUTANT');
    expect(cellule(fixture, 'B2', 'ateliers').getAttribute('data-niveau')).toBe('DEBUTANT');
    // « jeux » belongs to nobody's business here: Alice keeps her référent.
    expect(cellule(fixture, 'A1', 'jeux').getAttribute('data-niveau')).toBe('REFERENT');
    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('');
  });

  it('applies the column from the keyboard, and says when it changed nothing', async () => {
    const { fixture, notify } = mount();
    await fixture.whenStable();

    const active = cellule(fixture, 'A1', 'jeux');
    active.focus();
    await fixture.whenStable();
    combinaison(active, 'ArrowDown', { ctrlKey: true, shiftKey: true });
    await fixture.whenStable();
    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('REFERENT');

    // A second press has nothing left to do, and does not pretend otherwise.
    combinaison(active, 'ArrowDown', { ctrlKey: true, shiftKey: true });
    await fixture.whenStable();
    expect(notify).toHaveBeenCalledWith(
      expect.objectContaining({ variant: 'warning', title: expect.stringContaining('déjà') }),
    );
  });

  it('leaves AltGr+D alone: on an AZERTY keyboard it is a character, not a move', async () => {
    const { fixture } = mount();
    await fixture.whenStable();

    combinaison(cellule(fixture, 'B2', 'jeux'), 'd', { ctrlKey: true, altKey: true });
    await fixture.whenStable();

    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);
  });

  it('locks both moves while a solve runs', async () => {
    const { fixture } = mount({ editingLocked: true });
    await fixture.whenStable();

    expect(boutonLigne(fixture, 'B2').disabled).toBe(true);
    expect(boutonColonne(fixture, 'Ateliers').disabled).toBe(true);

    combinaison(cellule(fixture, 'B2', 'jeux'), 'd', { ctrlKey: true });
    await fixture.whenStable();
    expect(cellule(fixture, 'B2', 'jeux').getAttribute('data-niveau')).toBe('');
  });
});

// Rendering tests of the entry view: the moves live in `grille-horaires.ts`
// and are tested there; what is checked here is the wiring — one field per
// cell, a keystroke reaching the cells, a paste landing where the focus is,
// the save sending only what changed, and the lock while a solve runs.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StandsApi } from '../../core/api/stands-api';
import { ConsignesStore } from '../../core/consignes.store';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { EtatJourneesTypes, RapportOuvertures } from '../../core/models';
import { JourneesTypesApi } from '../../core/api/journees-types-api';
import { OuverturesPage } from './ouvertures-page';

/** The open segments of a fixture cell: none when closed, one hour out of two when partial. */
function segments(effectif: number | null, partial: boolean) {
  if (partial) {
    return [{ heureDebut: '10:00', heureFin: '11:00', effectif: effectif ?? 1 }];
  }
  return effectif === null ? [] : [{ heureDebut: '10:00', heureFin: '12:00', effectif }];
}

/** Two stands, two days of two créneaux each (10-12 and 14-20), ids 1-2 then 3-4. */
function rapport(): RapportOuvertures {
  const jour = (date: string, jour: number, ids: [number, number]) => ({
    date,
    jour,
    heureDebut: '10:00',
    heureFin: '20:00',
    minutes: 480,
    nombreCreneaux: 2,
    creneaux: [
      {
        id: ids[0],
        tranche: 0,
        heureDebut: '10:00',
        heureFin: '12:00',
        couverturePause: false,
      },
      {
        id: ids[1],
        tranche: 0,
        heureDebut: '14:00',
        heureFin: '20:00',
        couverturePause: false,
      },
    ],
  });
  const cellule = (creneauId: number, effectif: number | null, partiel = false) => ({
    creneauId,
    tranche: 0,
    effectif,
    partiel,
    // A partial cell of the fixture is open one hour out of two, at its headcount.
    segments: segments(effectif, partiel),
  });
  const jourStand = (date: string, creneaux: ReturnType<typeof cellule>[]) => ({
    date,
    etat: 'OUVERT_TOTAL' as const,
    source: 'REGLE' as const,
    fenetres: [{ heureDebut: '10:00', heureFin: '20:00' }],
    minutesOuvertes: 480,
    minutesAmplitude: 480,
    postes: 2,
    creneaux,
  });
  return {
    jours: [jour('2026-07-08', 1, [1, 2]), jour('2026-07-09', 2, [3, 4])],
    stands: [
      {
        standId: 'A',
        nom: 'Stand A',
        effectifMin: 2,
        jours: [
          jourStand('2026-07-08', [cellule(1, 2), cellule(2, 4)]),
          jourStand('2026-07-09', [cellule(3, 2), cellule(4, 4)]),
        ],
        minutesOuvertes: 960,
        postes: 4,
        modifieLe: '2026-09-06T10:00:00Z',
      },
      {
        standId: 'B',
        nom: 'Stand B',
        effectifMin: 1,
        jours: [
          jourStand('2026-07-08', [cellule(1, 1, true), cellule(2, null)]),
          jourStand('2026-07-09', [cellule(3, null), cellule(4, null)]),
        ],
        minutesOuvertes: 120,
        postes: 1,
        modifieLe: '2026-09-06T10:00:00Z',
      },
    ],
    standsJamaisOuverts: 0,
    postesTotal: 5,
    anomalies: [],
  };
}

/** Both days are « Jour normal »: 10-12 then 14-20, the shape the fixture repeats. */
function etatJourneesTypes(): EtatJourneesTypes {
  return {
    journeesTypes: [
      {
        id: 4,
        nom: 'Jour normal',
        vacations: [
          { heureDebut: '10:00:00', heureFin: '12:00:00', couverturePause: false },
          { heureDebut: '14:00:00', heureFin: '20:00:00', couverturePause: false },
        ],
      },
    ],
    calendrier: [
      { date: '2026-07-08', journeeTypeId: 4 },
      { date: '2026-07-09', journeeTypeId: 4 },
    ],
    datesEnEcart: [],
    datesSousConsigne: [],
  };
}

function mount(
  options: {
    vue?: string;
    q?: string;
    stand?: string;
    date?: string;
    editingLocked?: boolean;
    confirme?: boolean;
    rapport?: RapportOuvertures;
    journeesTypes?: EtatJourneesTypes | null;
  } = {},
) {
  const get = vi.fn(async () => options.rapport ?? rapport());
  const etatJT = vi.fn(async () => {
    const etat = options.journeesTypes === undefined ? etatJourneesTypes() : options.journeesTypes;
    if (etat === null) {
      throw new Error('pas de journées types');
    }
    return etat;
  });
  const put = vi.fn(async () => ({
    stands: [
      {
        standId: 'B',
        regles: 1,
        exceptions: 0,
        effectifMin: 3,
        effectifMax: 3,
        compacte: true,
        raison: '',
      },
    ],
  }));
  const ask = vi.fn(async () => options.confirme ?? true);
  const notify = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: StandsApi, useValue: { openings: get, saveOpeningsGrid: put } },
      {
        provide: ConsignesStore,
        useValue: {
          reload: vi.fn(async () => undefined),
          etat: () => null,
          consignes: () => [],
          aujourdhui: () => null,
          byDate: () => new Map(),
          creneauxAjoutes: () => new Set(),
          consigneOf: () => null,
        },
      },
      { provide: JourneesTypesApi, useValue: { etat: etatJT } },
      { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
      {
        provide: SolverJobService,
        useValue: { editingLocked: signal(options.editingLocked ?? false) },
      },
      { provide: ConfirmService, useValue: { ask } },
      { provide: NotificationService, useValue: { notify } },
      { provide: Location, useValue: { path: () => '/ouvertures', replaceState: vi.fn() } },
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            queryParamMap: convertToParamMap({
              ...(options.vue ? { vue: options.vue } : {}),
              ...(options.q ? { q: options.q } : {}),
              ...(options.stand ? { stand: options.stand } : {}),
              ...(options.date ? { date: options.date } : {}),
            }),
          },
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(OuverturesPage);
  return { fixture, get, put, ask, notify, etatJT };
}

function root(fixture: ComponentFixture<OuverturesPage>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

/** The column id of a créneau of the fixture: odd ids are 10-12, even ones 14-20. */
function colonne(creneauId: number): string {
  return `${creneauId}@${creneauId % 2 === 1 ? '10:00-12:00' : '14:00-20:00'}`;
}

function champ(
  fixture: ComponentFixture<OuverturesPage>,
  standId: string,
  creneauId: number,
  colonneId = colonne(creneauId),
): HTMLInputElement {
  const input = root(fixture).querySelector<HTMLInputElement>(
    `[data-cellule="${standId}#${colonneId}"]`,
  );
  expect(input, `case ${standId}#${colonneId}`).not.toBeNull();
  return input!;
}

/** Types into a cell: the events bubble, the grid listens on its body rather than on each of its four thousand cells. */
function taper(input: HTMLInputElement, valeur: string): void {
  input.value = valeur;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function quitter(input: HTMLInputElement): void {
  input.dispatchEvent(new FocusEvent('focusout', { bubbles: true }));
}

function bouton(fixture: ComponentFixture<OuverturesPage>, libelle: string): HTMLButtonElement {
  const trouve = Array.from(root(fixture).querySelectorAll('button')).find((each) =>
    each.textContent?.includes(libelle),
  );
  expect(trouve, `bouton « ${libelle} »`).toBeDefined();
  return trouve!;
}

describe('OuverturesPage — saisie', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => vi.restoreAllMocks());

  it('opens on the reading view, and on the entry view from ?vue=saisie', async () => {
    const lecture = mount();
    await lecture.fixture.whenStable();
    expect(root(lecture.fixture).querySelector('.grille-saisie')).toBeNull();
    expect(root(lecture.fixture).querySelector('.ouvertures-grille')).not.toBeNull();

    const saisie = mount({ vue: 'saisie' });
    await saisie.fixture.whenStable();
    expect(root(saisie.fixture).querySelector('.grille-saisie')).not.toBeNull();
  });

  /*
   * Each grid scrolls in a box of its own, which is the whole reason its
   * header and its stand column can stay put: `position: sticky` resolves
   * against the nearest scrolling ancestor, and `.table-wrapper` alone only
   * ever scrolls sideways. Rendered without CSS, this suite cannot judge what
   * that produces — `e2e/grilles-collantes.spec.ts` measures it in a browser —
   * but it can hold the class that turns it on, which a template edit drops
   * without anything else noticing.
   */
  /** `?q=`: a « Que faire ? » action opens the entry grid on the one stand in question. */
  it('restores the stand search from ?q=, so a link can open the grid on one stand', async () => {
    const { fixture } = mount({ vue: 'saisie', q: 'B' });
    await fixture.whenStable();
    const page = fixture.componentInstance as unknown as {
      lignes: () => { standId: string }[];
      recherche: () => string;
    };

    expect(page.recherche()).toBe('B');
    expect(page.lignes().map((ligne) => ligne.standId)).toEqual(['B']);
  });

  /** `?stand=`: the exact stand a « Que faire ? » action named, by name on screen, until « Tout afficher ». */
  it('narrows the grid to the exact stand of ?stand=, and lets it all back', async () => {
    const { fixture } = mount({ vue: 'saisie', stand: 'A' });
    await fixture.whenStable();
    const page = fixture.componentInstance as unknown as {
      lignes: () => { standId: string }[];
      onlyStandName: () => string;
      showAllStands: () => void;
    };

    expect(page.lignes().map((ligne) => ligne.standId)).toEqual(['A']);
    expect(root(fixture).textContent).toContain('Tout afficher');
    expect(page.onlyStandName()).not.toBe('A');

    page.showAllStands();
    await fixture.whenStable();
    expect(page.lignes().map((ligne) => ligne.standId)).toEqual(['A', 'B']);
  });

  /** `?date=` with `?stand=`: « Baisser l'effectif demandé » lands on the cell of that stand on that day. */
  it('focuses the first cell of ?date= on the stand of ?stand=, and keeps the day in the address', async () => {
    const { fixture } = mount({ vue: 'saisie', stand: 'A', date: '2026-07-09' });
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();

    expect(document.activeElement).toBe(champ(fixture, 'A', 3));
    const location = TestBed.inject(Location) as unknown as {
      replaceState: ReturnType<typeof vi.fn>;
    };
    expect(location.replaceState).toHaveBeenCalledWith(expect.stringContaining('date=2026-07-09'));
  });

  it('focuses the first row on ?date= alone, and forgets the day on leaving the entry view', async () => {
    const { fixture } = mount({ vue: 'saisie', date: '2026-07-09' });
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();

    expect(document.activeElement).toBe(champ(fixture, 'A', 3));
    const page = fixture.componentInstance as unknown as {
      changeView: (view: string) => Promise<void>;
      saisieDate: () => string;
    };
    await page.changeView('CONSULTER');
    expect(page.saisieDate()).toBe('');
  });

  it('wraps each grid in the scrolling box its sticky header needs', async () => {
    for (const [vue, grille] of [
      ['lecture', '.ouvertures-grille'],
      ['saisie', '.grille-saisie'],
      ['journees-types', '.grille-journees-types'],
    ] as const) {
      const { fixture } = mount(vue === 'lecture' ? {} : { vue });
      await fixture.whenStable();
      const table = root(fixture).querySelector(grille);
      expect(table, `grille de la vue ${vue}`).not.toBeNull();
      expect(
        table!.closest('.table-wrapper.grille-defilement'),
        `boîte de défilement de la vue ${vue}`,
      ).not.toBeNull();
    }
  });

  // The third view (ADR 0032): the same report, laid on time for one day.
  it('lays one day on the time axis from ?vue=journee, and jumps there from a day header', async () => {
    const journee = mount({ vue: 'journee' });
    await journee.fixture.whenStable();
    const racine = root(journee.fixture);
    expect(racine.querySelector('.axe-table')).not.toBeNull();
    expect(racine.querySelectorAll('.axe-ligne')).toHaveLength(2);
    // Stand A: two blocks on the first day, headcounts 2 and 4; stand B one partial hour.
    const blocs = [...racine.querySelectorAll('.axe-ligne')].map((ligne) =>
      [...ligne.querySelectorAll('.axe-bloc-label')].map((bloc) => bloc.textContent!.trim()),
    );
    expect(blocs).toEqual([['2', '4'], ['1']]);
    expect(racine.querySelectorAll('.axe-bande')).toHaveLength(2);

    const lecture = mount();
    await lecture.fixture.whenStable();
    (root(lecture.fixture).querySelectorAll('.voir-journee')[1] as HTMLButtonElement).click();
    await lecture.fixture.whenStable();
    expect(root(lecture.fixture).querySelector('.axe-table')).not.toBeNull();
    expect(
      (root(lecture.fixture).querySelector('.axe-jour-select mat-select') as HTMLElement)
        .textContent,
    ).toContain('09/07');
  });

  it('renders one field per stand and créneau, filled from the report, partial cells marked', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    expect(root(fixture).querySelectorAll('.grille-saisie input')).toHaveLength(8);
    expect(champ(fixture, 'A', 2).value).toBe('4');
    expect(champ(fixture, 'B', 2).value).toBe('-');
    expect(champ(fixture, 'B', 2).closest('td')!.classList.contains('cellule-fermee')).toBe(true);
    expect(champ(fixture, 'B', 1).closest('td')!.classList.contains('cellule-partielle')).toBe(
      true,
    );
    expect(champ(fixture, 'A', 1).closest('td')!.classList.contains('cellule-partielle')).toBe(
      false,
    );
    // The créneau header row reads as hours, the day header spans its créneaux.
    const entetes = Array.from(
      root(fixture).querySelectorAll('.entete-creneau .libelle-colonne'),
    ).map((each) => each.textContent!.trim());
    expect(entetes).toEqual(['10-12', '14-20', '10-12', '14-20']);
    expect(root(fixture).querySelector('.entete-jour-saisie')!.getAttribute('colspan')).toBe('2');
  });

  it('marks a typed cell and its row as modified, and counts the stand on the save button', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    taper(champ(fixture, 'B', 2), '3');
    await fixture.whenStable();

    expect(champ(fixture, 'B', 2).closest('td')!.classList.contains('cellule-modifiee')).toBe(true);
    expect(champ(fixture, 'B', 2).closest('tr')!.classList.contains('ligne-modifiee')).toBe(true);
    expect(champ(fixture, 'A', 2).closest('td')!.classList.contains('cellule-modifiee')).toBe(
      false,
    );
    expect(bouton(fixture, 'Enregistrer').textContent).toContain('(1)');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(false);
  });

  it('keeps the value of a field emptied, shows it as the placeholder, and closes on a dash or a zero', async () => {
    const { fixture, put } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    // Emptying says nothing: the cell keeps its 4, shown greyed, and nothing is modified.
    taper(champ(fixture, 'A', 2), '');
    await fixture.whenStable();
    expect(champ(fixture, 'A', 2).placeholder).toBe('4');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);
    quitter(champ(fixture, 'A', 2));
    expect(champ(fixture, 'A', 2).value).toBe('4');

    // A dash closes, and the field then reads « - »; a zero closes too.
    taper(champ(fixture, 'A', 2), '-');
    await fixture.whenStable();
    expect(champ(fixture, 'A', 2).closest('td')!.classList.contains('cellule-modifiee')).toBe(true);
    quitter(champ(fixture, 'A', 2));
    expect(champ(fixture, 'A', 2).value).toBe('-');
    taper(champ(fixture, 'A', 4), '0');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();
    const [stands] = put.mock.calls[0] as unknown as [
      { cellules: { effectif: number | null }[] }[],
    ];
    expect(stands[0].cellules.map((cellule) => cellule.effectif)).toEqual([2, null, 2, null]);
  });

  it('moves the focus down on Enter, right on the arrow past the caret, and leaves a letter alone', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    const depart = champ(fixture, 'A', 1);
    depart.focus();
    depart.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
    );
    expect(document.activeElement).toBe(champ(fixture, 'B', 1));

    const droite = champ(fixture, 'B', 1);
    droite.setSelectionRange(droite.value.length, droite.value.length);
    droite.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }),
    );
    expect(document.activeElement).toBe(champ(fixture, 'B', 2));

    const lettre = new KeyboardEvent('keydown', { key: 'a', bubbles: true, cancelable: true });
    champ(fixture, 'B', 2).dispatchEvent(lettre);
    expect(lettre.defaultPrevented).toBe(false);
  });

  it('lays a pasted block from the focused cell, and lets a single value paste as typed', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    const bloc = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent;
    Object.defineProperty(bloc, 'clipboardData', { value: { getData: () => '5\t6\n7\t8' } });
    champ(fixture, 'A', 1).dispatchEvent(bloc);
    await fixture.whenStable();

    expect(bloc.defaultPrevented).toBe(true);
    expect([champ(fixture, 'A', 1).value, champ(fixture, 'A', 2).value]).toEqual(['5', '6']);
    expect([champ(fixture, 'B', 1).value, champ(fixture, 'B', 2).value]).toEqual(['7', '8']);

    const simple = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent;
    Object.defineProperty(simple, 'clipboardData', { value: { getData: () => '9' } });
    champ(fixture, 'A', 1).dispatchEvent(simple);
    expect(simple.defaultPrevented).toBe(false);
  });

  it('copies a day onto the others from its header, and a row from the focused day', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    taper(champ(fixture, 'A', 3), '7');
    await fixture.whenStable();
    root(fixture).querySelectorAll<HTMLButtonElement>('.recopier-jour')[1].click();
    await fixture.whenStable();
    expect(champ(fixture, 'A', 1).value).toBe('7');
    expect(champ(fixture, 'B', 1).value).toBe('-');

    champ(fixture, 'B', 4).focus();
    taper(champ(fixture, 'B', 4), '2');
    await fixture.whenStable();
    root(fixture).querySelectorAll<HTMLButtonElement>('.recopier-ligne')[1].click();
    await fixture.whenStable();
    expect(champ(fixture, 'B', 2).value).toBe('2');
    // The row copy touches its own row only.
    expect(champ(fixture, 'A', 2).value).toBe('4');
  });

  it('takes the row above into this one, on the button and on Ctrl+D', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    const boutons = root(fixture).querySelectorAll<HTMLButtonElement>('.dupliquer-ligne');
    // The first displayed row has nothing above it.
    expect(boutons[0].disabled).toBe(true);
    expect(boutons[1].disabled).toBe(false);

    boutons[1].click();
    await fixture.whenStable();

    // Stand B takes stand A's whole week, on every displayed column.
    expect(champ(fixture, 'B', 1).value).toBe('2');
    expect(champ(fixture, 'B', 2).value).toBe('4');
    expect(champ(fixture, 'B', 3).value).toBe('2');
    expect(champ(fixture, 'B', 4).value).toBe('4');
    // And stand A is left exactly as it was.
    expect(champ(fixture, 'A', 1).value).toBe('2');
    expect(bouton(fixture, 'Enregistrer').textContent).toContain('(1)');
  });

  it('applies the active cell to its whole column, and leaves the other columns alone', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    champ(fixture, 'A', 2).focus();
    await fixture.whenStable();
    root(fixture).querySelectorAll<HTMLButtonElement>('.appliquer-colonne')[1].click();
    await fixture.whenStable();

    expect(champ(fixture, 'B', 2).value).toBe('4');
    // The 10-12 column of the same day says what it always said.
    expect(champ(fixture, 'B', 1).value).toBe('1');
    expect(champ(fixture, 'B', 4).value).toBe('-');
  });

  it('applies a column from the keyboard, from the cell the focus is in', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    const active = champ(fixture, 'A', 4);
    active.focus();
    taper(active, '6');
    await fixture.whenStable();
    active.dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 'ArrowDown',
        ctrlKey: true,
        shiftKey: true,
        bubbles: true,
        cancelable: true,
      }),
    );
    await fixture.whenStable();

    expect(champ(fixture, 'B', 4).value).toBe('6');
    expect(champ(fixture, 'B', 3).value).toBe('-');
  });

  it('leaves AltGr+D alone: on an AZERTY keyboard it is a character, not a move', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    champ(fixture, 'B', 1).dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 'd',
        ctrlKey: true,
        altKey: true,
        bubbles: true,
        cancelable: true,
      }),
    );
    await fixture.whenStable();

    expect(champ(fixture, 'B', 2).value).toBe('-');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);
  });

  it('sends only the modified stands, each with all its cells, then reloads and reports', async () => {
    const { fixture, put, get, notify, ask } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    taper(champ(fixture, 'A', 2), '5');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    // Stand A has no partial cell: no confirmation asked.
    expect(ask).not.toHaveBeenCalled();
    expect(put).toHaveBeenCalledWith([
      {
        standId: 'A',
        modifieLe: '2026-09-06T10:00:00Z',
        cellules: [
          { creneauId: 1, heureDebut: '10:00', heureFin: '12:00', effectif: 2 },
          { creneauId: 2, heureDebut: '14:00', heureFin: '20:00', effectif: 5 },
          { creneauId: 3, heureDebut: '10:00', heureFin: '12:00', effectif: 2 },
          { creneauId: 4, heureDebut: '14:00', heureFin: '20:00', effectif: 4 },
        ],
        aplatir: false,
      },
    ]);
    expect(get).toHaveBeenCalledTimes(2);
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
  });

  it('saves a partial cell left as shown without asking, and asks when that cell itself was retyped', async () => {
    const { fixture, put, ask } = mount({ vue: 'saisie', confirme: false });
    await fixture.whenStable();

    // The partial cell of B reads "1" and says what it holds.
    expect(champ(fixture, 'B', 1).getAttribute('title')).toContain('10:00-11:00 : 1');

    // Typing next to it: the partial cell travels unchanged, nothing to confirm.
    taper(champ(fixture, 'B', 2), '3');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();
    expect(ask).not.toHaveBeenCalled();
    expect(put).toHaveBeenCalledOnce();
    expect((put.mock.calls[0] as unknown as [{ aplatir: boolean }[]])[0][0].aplatir).toBe(false);

    // Retyping the partial cell itself asks, and a refusal writes nothing.
    taper(champ(fixture, 'B', 1), '2');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();
    expect(ask).toHaveBeenCalledOnce();
    expect((ask.mock.calls[0] as unknown as [{ message: string }])[0].message).toContain('B');
    expect(put).toHaveBeenCalledOnce();
  });

  it('aligns every partial stand in one click, pricing it first and sending aplatir', async () => {
    const { fixture, put, ask } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    const aligner = bouton(fixture, 'Aligner les fenêtres partielles');
    // Only stand B has a partial cell: counted, and the only one sent.
    expect(aligner.textContent).toContain('(1)');
    expect(aligner.disabled).toBe(false);
    aligner.click();
    await fixture.whenStable();

    expect(ask).toHaveBeenCalledOnce();
    const message = (ask.mock.calls[0] as unknown as [{ message: string }])[0].message;
    // One cell, one hour out of two at headcount 1: one hour of opening added.
    expect(message).toContain('1 stand(s), 1 case(s)');
    expect(message).toContain('1 h');
    expect(message).toContain('B');
    expect(put).toHaveBeenCalledWith([
      {
        standId: 'B',
        modifieLe: '2026-09-06T10:00:00Z',
        cellules: [
          { creneauId: 1, heureDebut: '10:00', heureFin: '12:00', effectif: 1 },
          { creneauId: 2, heureDebut: '14:00', heureFin: '20:00', effectif: null },
          { creneauId: 3, heureDebut: '10:00', heureFin: '12:00', effectif: null },
          { creneauId: 4, heureDebut: '14:00', heureFin: '20:00', effectif: null },
        ],
        aplatir: true,
      },
    ]);
  });

  it('refuses to align while cells are modified, and writes nothing when the alignment is refused', async () => {
    const { fixture, put } = mount({ vue: 'saisie', confirme: false });
    await fixture.whenStable();

    taper(champ(fixture, 'A', 2), '5');
    await fixture.whenStable();
    expect(bouton(fixture, 'Aligner les fenêtres partielles').disabled).toBe(true);

    bouton(fixture, 'Annuler les modifications').click();
    await fixture.whenStable();
    bouton(fixture, 'Aligner les fenêtres partielles').click();
    await fixture.whenStable();
    expect(put).not.toHaveBeenCalled();
  });

  it('discards the changes on demand, and asks before leaving the entry view with some', async () => {
    const { fixture, ask } = mount({ vue: 'saisie', confirme: false });
    await fixture.whenStable();

    taper(champ(fixture, 'B', 2), '3');
    await fixture.whenStable();
    bouton(fixture, 'Annuler les modifications').click();
    await fixture.whenStable();
    expect(champ(fixture, 'B', 2).value).toBe('-');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);

    taper(champ(fixture, 'B', 2), '3');
    await fixture.whenStable();
    (
      fixture.componentInstance as unknown as { changeView(view: string): Promise<void> }
    ).changeView('CONSULTER');
    await fixture.whenStable();
    expect(ask).toHaveBeenCalledOnce();
    // Refused: still on the entry view, cells intact.
    expect(root(fixture).querySelector('.grille-saisie')).not.toBeNull();
    expect(champ(fixture, 'B', 2).value).toBe('3');
  });

  it('cuts a column from its header, carries the values, and saves a cell of the cut with its bounds', async () => {
    const { fixture, put, notify } = mount({ vue: 'saisie' });
    await fixture.whenStable();

    // The scissor of the second column of day 1 (14-20) reveals the hour field.
    const entetes = () =>
      Array.from(root(fixture).querySelectorAll<HTMLElement>('.entete-creneau'));
    entetes()[1].querySelector<HTMLButtonElement>('.scinder-colonne')!.click();
    await fixture.whenStable();
    const heure = entetes()[1].querySelector<HTMLInputElement>('.scission-heure')!;
    expect(heure).not.toBeNull();

    // An hour on the edge cuts nothing and says so.
    heure.value = '20:00';
    heure.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'warning' }));
    expect(entetes()).toHaveLength(4);

    entetes()[1].querySelector<HTMLButtonElement>('.scinder-colonne')!.click();
    await fixture.whenStable();
    const champHeure = entetes()[1].querySelector<HTMLInputElement>('.scission-heure')!;
    champHeure.value = '19:00';
    champHeure.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    // Five columns, the day header spanning three, both halves reading the old value, nothing modified.
    expect(
      entetes().map((each) => each.querySelector('.libelle-colonne')!.textContent!.trim()),
    ).toEqual(['10-12', '14-19', '19-20', '10-12', '14-20']);
    expect(root(fixture).querySelector('.entete-jour-saisie')!.getAttribute('colspan')).toBe('3');
    expect(champ(fixture, 'A', 2, '2@14:00-19:00').value).toBe('4');
    expect(champ(fixture, 'A', 2, '2@19:00-20:00').value).toBe('4');
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);

    // Typing under the second half sends that half with its own bounds.
    taper(champ(fixture, 'A', 2, '2@19:00-20:00'), '2');
    await fixture.whenStable();
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();
    const [stands] = put.mock.calls[0] as unknown as [
      {
        cellules: {
          creneauId: number;
          heureDebut: string;
          heureFin: string;
          effectif: number | null;
        }[];
      }[],
    ];
    expect(stands[0].cellules.slice(1, 3)).toEqual([
      { creneauId: 2, heureDebut: '14:00', heureFin: '19:00', effectif: 4 },
      { creneauId: 2, heureDebut: '19:00', heureFin: '20:00', effectif: 2 },
    ]);
  });

  it('filters by hiding rows a beat after the keystroke, keeping every cell and its typed value', async () => {
    const { fixture } = mount({ vue: 'saisie' });
    await fixture.whenStable();
    taper(champ(fixture, 'A', 2), '5');
    await fixture.whenStable();

    const filtre = root(fixture).querySelector<HTMLInputElement>('input[name="recherche"]')!;
    filtre.value = 'Stand B';
    filtre.dispatchEvent(new Event('input', { bubbles: true }));
    await fixture.whenStable();
    // Not yet: the grid follows the field 150 ms after the last keystroke.
    expect(champ(fixture, 'A', 1).closest('tr')!.classList.contains('ligne-masquee')).toBe(false);
    await new Promise((resolve) => setTimeout(resolve, 200));
    await fixture.whenStable();

    expect(champ(fixture, 'A', 1).closest('tr')!.classList.contains('ligne-masquee')).toBe(true);
    expect(champ(fixture, 'B', 1).closest('tr')!.classList.contains('ligne-masquee')).toBe(false);
    // Hidden, not removed: the cells and the value typed before filtering are still there.
    expect(root(fixture).querySelectorAll('.grille-saisie input')).toHaveLength(8);
    expect(champ(fixture, 'A', 2).value).toBe('5');
    expect(bouton(fixture, 'Enregistrer').textContent).toContain('(1)');
  });

  it('locks every field and the save while a solve is running, and says so', async () => {
    const { fixture } = mount({ vue: 'saisie', editingLocked: true });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.locked-hint')).not.toBeNull();
    expect(
      Array.from(root(fixture).querySelectorAll<HTMLInputElement>('.grille-saisie input')).every(
        (each) => each.disabled,
      ),
    ).toBe(true);
    expect(bouton(fixture, 'Enregistrer').disabled).toBe(true);
  });
});

// The grid by kind of day: the same cells, said once per template. What is
// checked here is the wiring — the columns, one keystroke reaching every date,
// and the drift a cell cannot hold.
describe('OuverturesPage — grille par journée type', () => {
  it('montre une colonne par vacation de la journée type, pas une par date', async () => {
    const { fixture } = mount({ vue: 'journees-types' });
    await fixture.whenStable();

    const entetes = Array.from(
      root(fixture).querySelectorAll('.grille-journees-types .entete-creneau .libelle-colonne'),
    ).map((each) => each.textContent!.trim());
    expect(entetes).toEqual(['10:00-12:00', '14:00-20:00']);
    expect(
      root(fixture).querySelector('.grille-journees-types .entete-jour')!.textContent!.trim(),
    ).toBe('Jour normal');
  });

  it('écrit une case sur toutes les dates de sa journée type', async () => {
    const { fixture, put } = mount({ vue: 'journees-types' });
    await fixture.whenStable();

    taper(champ(fixture, 'A', 0, 'jt:4@14:00-20:00'), '5');
    await fixture.whenStable();

    expect(bouton(fixture, 'Enregistrer').textContent).toContain('(1)');
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    const [envoye] = put.mock.calls[0] as unknown as [
      { standId: string; cellules: { creneauId: number; effectif: number | null }[] }[],
    ];
    expect(envoye).toHaveLength(1);
    // Both 14-20 slots of the edition, one per date the template governs.
    expect(
      envoye[0].cellules
        .filter((cellule) => cellule.effectif === 5)
        .map((cellule) => cellule.creneauId),
    ).toEqual([2, 4]);
  });

  it('annonce « ≠ » quand deux dates de la même journée type divergent', async () => {
    const divergent = rapport();
    divergent.stands[0].jours[1].creneaux[1].effectif = 3;

    const { fixture } = mount({ vue: 'journees-types', rapport: divergent });
    await fixture.whenStable();

    const case14 = champ(fixture, 'A', 0, 'jt:4@14:00-20:00');
    expect(case14.value).toBe('≠');
    expect(case14.closest('td')!.classList.contains('cellule-ecart')).toBe(true);
  });

  it('applique une colonne de vacation à toutes les dates de la journée type', async () => {
    const { fixture, put } = mount({ vue: 'journees-types' });
    await fixture.whenStable();

    champ(fixture, 'A', 0, 'jt:4@14:00-20:00').focus();
    await fixture.whenStable();
    root(fixture)
      .querySelectorAll<HTMLButtonElement>('.grille-journees-types .appliquer-colonne')[1]
      .click();
    await fixture.whenStable();

    expect(champ(fixture, 'B', 0, 'jt:4@14:00-20:00').value).toBe('4');
    bouton(fixture, 'Enregistrer').click();
    await fixture.whenStable();

    const [envoye] = put.mock.calls[0] as unknown as [
      { standId: string; cellules: { creneauId: number; effectif: number | null }[] }[],
    ];
    expect(envoye.map((stand) => stand.standId)).toEqual(['B']);
    // Both 14-20 slots of the edition, one per date the template governs.
    expect(
      envoye[0].cellules
        .filter((cellule) => cellule.effectif === 4)
        .map((cellule) => cellule.creneauId),
    ).toEqual([2, 4]);
  });

  it('refuse de propager une case dont les dates divergent, et le dit', async () => {
    const divergent = rapport();
    divergent.stands[0].jours[1].creneaux[1].effectif = 3;

    const { fixture, notify } = mount({ vue: 'journees-types', rapport: divergent });
    await fixture.whenStable();

    champ(fixture, 'A', 0, 'jt:4@14:00-20:00').focus();
    await fixture.whenStable();
    root(fixture)
      .querySelectorAll<HTMLButtonElement>('.grille-journees-types .appliquer-colonne')[1]
      .click();
    await fixture.whenStable();

    expect(notify).toHaveBeenCalledWith(
      expect.objectContaining({ variant: 'warning', title: expect.stringContaining('propager') }),
    );
    expect(champ(fixture, 'B', 0, 'jt:4@14:00-20:00').value).toBe('-');
  });

  it('reste sur la grille par date quand l’édition n’a pas de journées types', async () => {
    const { fixture } = mount({ vue: 'journees-types', journeesTypes: null });
    await fixture.whenStable();

    expect(root(fixture).querySelector('.grille-journees-types')).toBeNull();
  });
});

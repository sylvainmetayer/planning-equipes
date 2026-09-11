// Two halves. The first one is a logic test of the alert badges (the component
// is created, never rendered). The second one renders the page, because the
// claims that matter to a user live in the table and nowhere else: that "tout
// sélectionner" ticks the *displayed* rows and not the referential — the
// difference between deleting four animateurs and deleting a hundred and
// fifty — that the solver lock really greys the writing actions out, and that
// an empty table says why it is empty.

import { provideZonelessChangeDetection, signal, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { NotificationService } from '../../core/notification.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { DetailDialog } from '../../shared/detail-dialog';
import { AnimateurFormDialog } from './animateur-form-dialog';
import { AnimateursPage } from './animateurs-page';
import type { Animateur, CauseInfaisabilite, FeasibilityReport } from '../../core/models';
import { seedStore } from '../../core/testing/seed-store';

function animateur(id: string, joursIndisponibles: string[]): Animateur {
  return {
    id,
    prenom: id,
    nom: id.toUpperCase(),
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles,
  };
}

function cause(severite: CauseInfaisabilite['severite'], date: string): CauseInfaisabilite {
  return {
    type: 'CRENEAU_SOUS_EFFECTIF',
    severite,
    message: `Manque d'animateurs le ${date}.`,
    creneauId: '12',
    date,
    heureDebut: '12:30',
    heureFin: '15:30',
    standIds: ['tir'],
    contrainteIds: [],
    demande: 6,
    capacite: 4,
    manque: 2,
  };
}

function report(causes: CauseInfaisabilite[]): FeasibilityReport {
  return {
    feasible: false,
    manqueAnimateurs: 2,
    causes,
    totalCauses: causes.length,
    message: 'Planning non réalisable en l’état.',
  };
}

/** Reaches the protected computed the template binds to. */
type PageInternals = { alerteParAnimateurId: Signal<Map<string, string>> };

const SYNTHESE_VIDE = {
  confirmes: 0,
  relances: 0,
  silencieux: 0,
  dernierePublicationLe: null,
  jamaisPublie: true,
};

describe('AnimateursPage alert badges', () => {
  let referenceData: ReferenceDataStore;
  let problemes: ProblemesStore;
  const api = { get: vi.fn(async () => report([])) };
  const animateursApi = {
    regenerateToken: vi.fn(async () => undefined),
    confirmations: vi.fn(async () => []),
    syntheseConfirmations: vi.fn(async () => SYNTHESE_VIDE),
  };

  beforeEach(() => {
    api.get.mockReset();
    api.get.mockResolvedValue(report([]));
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Router, useValue: { navigate: vi.fn(async () => true) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        { provide: ApiService, useValue: api },
        { provide: AnimateursApi, useValue: animateursApi },
        { provide: ReferenceCrudService, useValue: { reload: vi.fn(async () => undefined) } },
        {
          provide: SolverJobService,
          useValue: { solverBusy: () => false, editingLocked: () => false },
        },
        { provide: MatDialog, useValue: { open: vi.fn() } },
      ],
    });
    referenceData = TestBed.inject(ReferenceDataStore);
    problemes = TestBed.inject(ProblemesStore);
  });

  function createPage(): PageInternals {
    return TestBed.createComponent(AnimateursPage).componentInstance as unknown as PageInternals;
  }

  it('flags nobody while no diagnostic is loaded', () => {
    seedStore(referenceData, 'animateurs', [animateur('alice', ['2026-08-01'])]);
    expect(createPage().alerteParAnimateurId().size).toBe(0);
  });

  it('flags an animateur unavailable on a day carrying a CRITIQUE cause', async () => {
    seedStore(referenceData, 'animateurs', [
      animateur('alice', ['2026-08-01']),
      animateur('bob', ['2026-08-05']),
    ]);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('CRITIQUE', '2026-08-01')]));
    await problemes.reloadFeasibility();

    expect([...page.alerteParAnimateurId().keys()]).toEqual(['alice']);
    expect(page.alerteParAnimateurId().get('alice')).toContain('2026-08-01');
    expect(page.alerteParAnimateurId().get('alice')).toContain("Manque d'animateurs");
  });

  it('ignores a day that is only ELEVE', async () => {
    seedStore(referenceData, 'animateurs', [animateur('alice', ['2026-08-01'])]);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('ELEVE', '2026-08-01')]));
    await problemes.reloadFeasibility();

    expect(page.alerteParAnimateurId().size).toBe(0);
  });

  it('ignores an animateur available on every critical day', async () => {
    seedStore(referenceData, 'animateurs', [animateur('alice', [])]);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('CRITIQUE', '2026-08-01')]));
    await problemes.reloadFeasibility();

    expect(page.alerteParAnimateurId().size).toBe(0);
  });

  it('recomputes when the roster changes', async () => {
    seedStore(referenceData, 'animateurs', []);
    const page = createPage();

    api.get.mockResolvedValue(report([cause('CRITIQUE', '2026-08-01')]));
    await problemes.reloadFeasibility();
    expect(page.alerteParAnimateurId().size).toBe(0);

    seedStore(referenceData, 'animateurs', [animateur('carole', ['2026-08-01'])]);
    expect(page.alerteParAnimateurId().has('carole')).toBe(true);
  });
});

describe('AnimateursPage table', () => {
  let referenceData: ReferenceDataStore;
  let fixture: ComponentFixture<AnimateursPage>;
  let dialog: { open: ReturnType<typeof vi.fn> };
  let confirm: { ask: ReturnType<typeof vi.fn> };
  let notify: ReturnType<typeof vi.fn>;
  let api: { get: ReturnType<typeof vi.fn> };
  let animateursApi: {
    regenerateToken: ReturnType<typeof vi.fn>;
    confirmations: ReturnType<typeof vi.fn>;
    syntheseConfirmations: ReturnType<typeof vi.fn>;
    remind: ReturnType<typeof vi.fn>;
  };
  const editingLocked = signal(false);

  function personne(id: string, overrides: Partial<Animateur> = {}): Animateur {
    return { ...animateur(id, []), ...overrides };
  }

  async function rendre(animateurs: Animateur[]): Promise<void> {
    seedStore(referenceData, 'animateurs', animateurs);
    fixture = TestBed.createComponent(AnimateursPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  /** Text of every body row, cell by cell. */
  function lignes(): string[][] {
    return Array.from(racine().querySelectorAll('tbody tr')).map((row) =>
      Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent!.trim()),
    );
  }

  /** Action buttons of one row, by their accessible name. */
  function action(indexLigne: number, nom: string): HTMLButtonElement {
    const boutons = Array.from(
      racine().querySelectorAll('tbody tr')[indexLigne].querySelectorAll('.row-actions button'),
    );
    const bouton = boutons.find((each) => each.getAttribute('aria-label') === nom);
    expect(bouton, `action « ${nom} » absente`).toBeDefined();
    return bouton as HTMLButtonElement;
  }

  /** Header cell texts, in display order. */
  function entetes(): string[] {
    return Array.from(racine().querySelectorAll('thead th')).map((cell) =>
      cell.textContent!.trim(),
    );
  }

  /** Clicks the sort header whose label starts with `libelle`. */
  async function sort(libelle: string): Promise<void> {
    const entete = Array.from(
      racine().querySelectorAll<HTMLElement>('thead th[mat-sort-header]'),
    ).find((cell) => cell.textContent!.trim().startsWith(libelle));
    expect(entete, `en-tête triable « ${libelle} » absent`).toBeDefined();
    (entete!.querySelector('.mat-sort-header-container') as HTMLElement).click();
    await fixture.whenStable();
  }

  async function filter(text: string): Promise<void> {
    const input = racine().querySelector('app-table-filter input') as HTMLInputElement;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    editingLocked.set(false);
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
    confirm = { ask: vi.fn(async () => false) };
    notify = vi.fn();
    // The referential endpoints answer a list; only /api/feasibility answers a report.
    api = {
      get: vi.fn(async (url: string) => (url.includes('feasibility') ? report([]) : [])),
    };
    animateursApi = {
      regenerateToken: vi.fn(async () => undefined),
      confirmations: vi.fn(async () => []),
      syntheseConfirmations: vi.fn(async () => SYNTHESE_VIDE),
      remind: vi.fn(async () => ({
        envoyes: [],
        dejaConfirmes: [],
        sansEmail: [],
        dejaRelancesPourCettePublication: [],
        echecs: [],
        sansPoste: [],
      })),
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: Router, useValue: { navigate: vi.fn(async () => true) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        { provide: ApiService, useValue: api },
        { provide: AnimateursApi, useValue: animateursApi },
        {
          provide: ReferenceCrudService,
          useValue: {
            reload: vi.fn(async () => undefined),
            remove: vi.fn(async () => true),
            removeMany: vi.fn(async () => 0),
          },
        },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked } },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: { notify } },
      ],
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  it('renders one row per animateur, with the derived majority and no appreciation column', async () => {
    await rendre([
      personne('alice', {
        prenom: 'Amélie',
        nom: 'Nothomb',
        dateNaissance: '1990-05-04',
        manager: true,
        competences: { ambiance: 'REFERENT' },
      }),
      personne('bob', { prenom: 'Bob', nom: 'Ados', dateNaissance: '2015-01-01' }),
    ]);

    expect(racine().querySelector('h1')!.textContent!).toContain('Animateurs (2)');
    // Majority is derived from the birth date, never stored.
    expect(lignes()[0].slice(1, 5)).toEqual(['alice', 'Amélie Nothomb', 'Oui', 'Oui']);
    expect(lignes()[1].slice(1, 5)).toEqual(['bob', 'Bob Ados', 'Non', 'Non']);
    // The appreciation is a list per row: unreadable in a cell, and read in the
    // detail dialog instead. No cell may spell it out again.
    expect(lignes().flat().join(' ')).not.toContain('REFERENT');
    expect(entetes()).not.toContain('Appréciation');
  });

  it('says the majority is unknown rather than guessing it without a birth date', async () => {
    await rendre([personne('alice', { dateNaissance: '' })]);

    expect(lignes()[0][3]).toBe('—');
  });

  it('sorts on the majority column, both ways', async () => {
    await rendre([
      personne('mineur', { dateNaissance: '2015-01-01' }),
      personne('inconnu', { dateNaissance: '' }),
      personne('majeur', { dateNaissance: '1990-01-01' }),
    ]);

    await sort('Majeur');
    expect(lignes().map((row) => row[1])).toEqual(['majeur', 'mineur', 'inconnu']);

    await sort('Majeur');
    expect(lignes().map((row) => row[1])).toEqual(['inconnu', 'mineur', 'majeur']);
  });

  it('sorts the identifiers as numbers where they carry one, not as text', async () => {
    await rendre([personne('A10'), personne('A2'), personne('A1')]);

    await sort('Id');
    expect(lignes().map((row) => row[1])).toEqual(['A1', 'A2', 'A10']);
  });

  it('sorts the name column on what the cell shows, first name included', async () => {
    await rendre([
      personne('c', { prenom: 'Zoé', nom: 'Abadie' }),
      personne('a', { prenom: 'Élodie', nom: 'Blanc' }),
      personne('b', { prenom: 'Adrien', nom: 'Costa' }),
    ]);

    await sort('Nom');
    expect(lignes().map((row) => row[2])).toEqual(['Adrien Costa', 'Élodie Blanc', 'Zoé Abadie']);
  });

  it('brings the managers up first, like the majority column', async () => {
    await rendre([personne('a', { manager: false }), personne('b', { manager: true })]);

    await sort('Manager');
    expect(lignes().map((row) => row[1])).toEqual(['b', 'a']);
  });

  it('sorts the unavailability column on the number of days', async () => {
    await rendre([
      personne('trois', { joursIndisponibles: ['2026-07-01', '2026-07-02', '2026-07-03'] }),
      personne('aucune', { joursIndisponibles: [] }),
      personne('une', { joursIndisponibles: ['2026-07-01'] }),
    ]);

    await sort('Indisponibilités');
    expect(lignes().map((row) => row[1])).toEqual(['aucune', 'une', 'trois']);
  });

  it('puts what is left to chase on top of the acknowledgement sort', async () => {
    animateursApi.confirmations.mockResolvedValue([
      {
        animateurId: 'confirme',
        statut: 'CONFIRME',
        affecte: true,
        confirmeLe: null,
        relanceLe: null,
      },
      {
        animateurId: 'relance',
        statut: 'RELANCE',
        affecte: true,
        confirmeLe: null,
        relanceLe: null,
      },
      {
        animateurId: 'silencieux',
        statut: 'NON_VU',
        affecte: true,
        confirmeLe: null,
        relanceLe: null,
      },
      {
        animateurId: 'sansPoste',
        statut: 'NON_VU',
        affecte: false,
        confirmeLe: null,
        relanceLe: null,
      },
    ]);
    await rendre([
      personne('confirme'),
      personne('sansPoste'),
      personne('relance'),
      personne('silencieux'),
    ]);
    await fixture.whenStable();

    await sort('Accusé de réception');
    // Silencieux, relancé, confirmé — and last the person nothing was asked of.
    expect(lignes().map((row) => row[1])).toEqual([
      'silencieux',
      'relance',
      'confirme',
      'sansPoste',
    ]);
  });

  // « Relancer maintenant » (issue #504): mails leave, so the gesture is
  // confirmed first, and the report names who was written to and who was not.

  it('reminds the ticked rows only after an explicit confirmation, and reports by name', async () => {
    animateursApi.remind.mockResolvedValue({
      envoyes: ['alice'],
      dejaConfirmes: ['bob'],
      sansEmail: [],
      dejaRelancesPourCettePublication: [],
      echecs: [],
      sansPoste: [],
    });
    await rendre([
      personne('alice', { prenom: 'Alice', nom: 'Martin' }),
      personne('bob', { prenom: 'Bob', nom: 'Durand' }),
    ]);
    (racine().querySelector('thead mat-checkbox input') as HTMLInputElement).click();
    await fixture.whenStable();

    const relancer = Array.from(racine().querySelectorAll('app-bulk-actions-bar button')).find(
      (each) => each.textContent!.includes('Relancer maintenant'),
    ) as HTMLButtonElement;
    relancer.click();
    await fixture.whenStable();
    // Refused: no mail leaves on a click that was not confirmed.
    expect(animateursApi.remind).not.toHaveBeenCalled();
    expect(confirm.ask.mock.calls[0][0].title).toContain('2');

    confirm.ask.mockResolvedValue(true);
    relancer.click();
    await fixture.whenStable();
    expect(animateursApi.remind).toHaveBeenCalledExactlyOnceWith(['alice', 'bob']);
    const dernier = notify.mock.calls.at(-1)![0];
    expect(dernier.variant).toBe('success');
    expect(dernier.title).toBe('1 relance(s) envoyée(s)');
    expect(dernier.message).toBe('Déjà confirmés : Bob Durand');
    // The answers are reloaded so the column shows « Relancé » at once.
    expect(animateursApi.confirmations).toHaveBeenCalledTimes(2);
    expect(racine().querySelector('app-bulk-actions-bar')).toBeNull();
  });

  it('heads the page with the three counts and the last publication', async () => {
    animateursApi.syntheseConfirmations.mockResolvedValue({
      confirmes: 12,
      relances: 3,
      silencieux: 5,
      dernierePublicationLe: '2026-07-01T10:00:00Z',
      jamaisPublie: false,
    });
    await rendre([personne('alice')]);
    await fixture.whenStable();

    const synthese = racine().querySelector('.confirmations-synthese')!.textContent!;
    expect(synthese).toContain('Confirmés 12');
    expect(synthese).toContain('Relancés 3');
    expect(synthese).toContain('Silencieux 5');
    expect(synthese).toContain('Dernière publication le');
  });

  it('says the planning was never published rather than counting nobody', async () => {
    await rendre([personne('alice')]);
    await fixture.whenStable();

    expect(racine().querySelector('.confirmations-synthese')!.textContent!.trim()).toBe(
      'Jamais publié',
    );
  });

  it('opens the acknowledgement tooltip without sorting the column it sits in', async () => {
    await rendre([personne('alice')]);

    const aide = racine().querySelector('th .column-help') as HTMLButtonElement;
    aide.click();
    await fixture.whenStable();

    expect(racine().querySelectorAll('th[aria-sort]:not([aria-sort="none"])')).toHaveLength(0);
  });

  it('enters the table on arrow down from the quick filter', async () => {
    await rendre([personne('alice'), personne('bob')]);

    const champ = racine().querySelector('app-table-filter input') as HTMLInputElement;
    champ.focus();
    const touche = new KeyboardEvent('keydown', {
      key: 'ArrowDown',
      bubbles: true,
      cancelable: true,
    });
    champ.dispatchEvent(touche);
    await fixture.whenStable();

    expect(touche.defaultPrevented).toBe(true);
    expect((document.activeElement as HTMLElement).getAttribute('data-row-index')).toBe('0');
  });

  it('narrows the table on the quick filter, and says when nothing matches', async () => {
    await rendre([
      personne('alice', { prenom: 'Amélie', nom: 'Nothomb' }),
      personne('bob', { prenom: 'Bob', nom: 'Ados' }),
    ]);

    await filter('nothomb');
    expect(lignes().map((row) => row[1])).toEqual(['alice']);

    await filter('zzz');
    expect(lignes()).toEqual([]);
    expect(racine().querySelector('.empty-hint')!.textContent!.trim()).toBe(
      'Aucune ligne ne correspond au filtre.',
    );
  });

  it('says the referential is empty, not that the filter matched nothing', async () => {
    await rendre([]);

    expect(racine().querySelector('.empty-hint')!.textContent!.trim()).toBe(
      'Aucun animateur pour le moment.',
    );
  });

  it('ticks only the displayed rows on "tout sélectionner", and warns that the scope is filtered', async () => {
    await rendre([personne('alice', { nom: 'Nothomb' }), personne('bob', { nom: 'Ados' })]);

    await filter('nothomb');
    (racine().querySelector('thead mat-checkbox input') as HTMLInputElement).click();
    await fixture.whenStable();

    // The whole point of the warning: this is one click away from a bulk delete.
    expect(
      racine().querySelector('app-bulk-actions-bar .bulk-bar-count')!.textContent!.trim(),
    ).toBe('1 élément(s) sélectionné(s)');
    expect(racine().querySelector('.bulk-bar-scope')).not.toBeNull();
  });

  it('shows no bulk bar until something is ticked', async () => {
    await rendre([personne('alice')]);

    expect(racine().querySelector('app-bulk-actions-bar')).toBeNull();

    (racine().querySelector('tbody mat-checkbox input') as HTMLInputElement).click();
    await fixture.whenStable();
    expect(racine().querySelector('app-bulk-actions-bar')).not.toBeNull();
  });

  it('flags an unavailability falling on a structurally understaffed day, for a screen reader too', async () => {
    await rendre([personne('alice', { joursIndisponibles: ['2026-08-01'] })]);
    api.get.mockResolvedValue(report([cause('CRITIQUE', '2026-08-01')]));
    await TestBed.inject(ProblemesStore).reloadFeasibility();
    await fixture.whenStable();

    const icone = racine().querySelector('.row-problem-icon')!;
    expect(icone.getAttribute('aria-label')).toContain('2026-08-01');
  });

  it('offers the espace link only to the animateurs who have one', async () => {
    await rendre([personne('alice', { accessToken: 'jeton-1' }), personne('bob')]);

    expect(action(0, 'Copier le lien de son espace animateur').disabled).toBe(false);
    expect(action(1, 'Copier le lien de son espace animateur').disabled).toBe(true);
  });

  it('greys out every writing action while a solve is running, but not the read-only ones', async () => {
    await rendre([personne('alice', { accessToken: 'jeton-1' })]);
    editingLocked.set(true);
    await fixture.whenStable();

    expect(action(0, 'Modifier').disabled).toBe(true);
    expect(action(0, 'Supprimer').disabled).toBe(true);
    expect(action(0, 'Régénérer le lien de son espace').disabled).toBe(true);
    expect((racine().querySelector('mat-card-actions button') as HTMLButtonElement).disabled).toBe(
      true,
    );
    // Reading a row and copying a link change nothing: locking them would only
    // punish the user for the solver's duration.
    expect(action(0, 'Consulter le détail').disabled).toBe(false);
    expect(action(0, 'Copier le lien de son espace animateur').disabled).toBe(false);
  });

  it('opens the read-only detail, and hands over to the form when the user asks to edit', async () => {
    await rendre([personne('alice', { prenom: 'Amélie', nom: 'Nothomb' })]);
    dialog.open.mockReturnValue({ afterClosed: () => of('edit') });

    action(0, 'Consulter le détail').click();
    await fixture.whenStable();

    expect(dialog.open.mock.calls[0][0]).toBe(DetailDialog);
    expect(dialog.open.mock.calls[0][1].data.title).toBe('Amélie Nothomb');
    expect(dialog.open.mock.calls[1][0]).toBe(AnimateurFormDialog);
    expect(dialog.open.mock.calls[1][1].data.animateur.id).toBe('alice');
  });

  it('regenerates an espace token only after an explicit confirmation', async () => {
    await rendre([personne('alice', { accessToken: 'jeton-1' })]);

    action(0, 'Régénérer le lien de son espace').click();
    await fixture.whenStable();
    // Refused: the already-printed PDFs must keep working.
    expect(animateursApi.regenerateToken).not.toHaveBeenCalled();

    confirm.ask.mockResolvedValue(true);
    action(0, 'Régénérer le lien de son espace').click();
    await fixture.whenStable();
    expect(animateursApi.regenerateToken).toHaveBeenCalledExactlyOnceWith('alice');
    expect(notify.mock.calls.at(-1)![0].variant).toBe('success');
  });

  it('reports the failure, and the link itself, when the clipboard refuses', async () => {
    await rendre([personne('alice', { accessToken: 'jeton-1' })]);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn(async () => Promise.reject(new Error('denied'))) },
    });

    action(0, 'Copier le lien de son espace animateur').click();
    await fixture.whenStable();

    // A silent failure would leave the user thinking the link is in their buffer.
    const dernier = notify.mock.calls.at(-1)![0];
    expect(dernier.variant).toBe('warning');
    expect(dernier.message).toContain('/animateur/jeton-1');
  });

  // The invariant the acknowledgement column broke the day it became sortable.
  // Material names the sort control it generates from everything the header
  // cell contains, `aria-label`s of nested controls included: a header holding
  // a help button lends it its whole explanation, and a screen reader reads
  // those lines out on every pass of the focus. A header that holds a named
  // control must therefore name its sort control itself
  // (`shared/sort-header-name.ts`). Written as a sweep rather than a single
  // case: the next sortable column carrying an icon or a checkbox is the one
  // that would bring the defect back.
  it('never lets a sortable header take its name from a control it contains', async () => {
    await rendre([personne('alice')]);

    const triables = Array.from(
      racine().querySelectorAll<HTMLElement>('thead th[mat-sort-header]'),
    );
    expect(triables.length, 'colonnes triables rendues').toBeGreaterThan(0);

    for (const entete of triables) {
      const controles = Array.from(entete.querySelectorAll('[aria-label]'));
      if (controles.length === 0) {
        continue;
      }
      const conteneur = entete.querySelector('.mat-sort-header-container');
      expect(conteneur, 'conteneur de tri de Material').not.toBeNull();
      const nomme =
        conteneur!.hasAttribute('aria-label') || conteneur!.hasAttribute('aria-labelledby');
      expect(
        nomme,
        `l'en-tête « ${entete.textContent!.trim().slice(0, 30)} » contient un contrôle nommé ` +
          `(« ${controles[0].getAttribute('aria-label')!.slice(0, 40)}… ») et laisse son bouton de ` +
          `tri se nommer par son contenu : appliquer appSortHeaderName`,
      ).toBe(true);
    }
  });

  it('announces the acknowledgement header by its title, and the help by its explanation', async () => {
    await rendre([personne('alice')]);

    const entete = Array.from(
      racine().querySelectorAll<HTMLElement>('thead th[mat-sort-header]'),
    ).find((cell) => cell.textContent!.trim().startsWith('Accusé de réception'))!;
    const reference = entete
      .querySelector('.mat-sort-header-container')!
      .getAttribute('aria-labelledby');

    expect(racine().querySelector(`#${reference}`)!.textContent!.trim()).toBe(
      'Accusé de réception',
    );
    // The help keeps the whole thing: that is what issue #338 put there, and
    // what a screen reader must read when the focus reaches the button itself.
    expect(entete.querySelector('.column-help')!.getAttribute('aria-label')).toContain(
      "Ce que l'animateur a répondu",
    );
  });
});

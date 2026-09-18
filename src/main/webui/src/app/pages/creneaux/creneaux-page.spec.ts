// Sorting, the conditional « Famille » column, multi-selection, the feasibility
// index and the grid's verdict on the timeslots table. The first half creates
// the component without rendering it; the second one renders the page, because
// what guards the writing actions — a confirmation, a disabled button while a
// solve runs — only exists in the template.
//
// Written as the safety net the `<app-reference-table>` extraction needs.
// `jours-resume.spec.ts` owns the per-day summary; what is pinned here is the
// page that drives it.

import { provideZonelessChangeDetection, Signal, WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { Sort } from '@angular/material/sort';
import { provideRouter, Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CreneauxApi } from '../../core/api/creneaux-api';
import { ConsignesStore } from '../../core/consignes.store';
import { JourneesTypesApi } from '../../core/api/journees-types-api';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { ConfirmService } from '../../shared/confirm-dialog';
import { CauseInfaisabilite, Creneau } from '../../core/models';
import { CreneauxPage } from './creneaux-page';
import { seedStore } from '../../core/testing/seed-store';

function creneau(overrides: Partial<Creneau> & { id: number; jour: number }): Creneau {
  return { date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00', ...overrides };
}

function cause(manque: number): CauseInfaisabilite {
  return {
    type: 'CRENEAU_SOUS_EFFECTIF',
    severite: 'CRITIQUE',
    message: "Manque d'animateurs.",
    creneauId: '1',
    date: '2026-08-01',
    heureDebut: '10:00',
    heureFin: '12:00',
    standIds: [],
    contrainteIds: [],
    demande: 6,
    capacite: 6 - manque,
    manque,
  };
}

const DIAGNOSTIC = {
  nombreCreneaux: 1,
  premiereDate: '2026-08-01',
  derniereDate: '2026-08-01',
  contientCouverturePause: false,
  explication: '1 vacations sur 1 date(s), durée médiane de 120 min, aucun relais repas.',
};

const CONTROLE = {
  nombreCreneaux: 1,
  anomalies: [] as { severite: string; type: string; date: string | null; message: string }[],
  ouvertures: [] as unknown[],
  faisabilite: null,
};

/**
 * The page reads three endpoints on entry: the créneaux list is the store's
 * business, the two others answer here by URL.
 */
function reponseApi(
  url: string,
  autres: unknown = [],
  surcharges: { diagnostic?: object; controle?: object } = {},
): unknown {
  if (url.includes('/creneaux/diagnostic')) {
    return { ...DIAGNOSTIC, ...(surcharges.diagnostic ?? {}) };
  }
  if (url.includes('/creneaux/controle')) {
    return { ...CONTROLE, ...(surcharges.controle ?? {}) };
  }
  return autres;
}

/** Wires the fake resource service the way the URL dispatch used to: each read answers from {@link reponseApi}. */
function brancher(
  creneauxApi: {
    diagnostic: ReturnType<typeof vi.fn>;
    control: ReturnType<typeof vi.fn>;
  },
  autres: unknown = [],
  surcharges: { diagnostic?: object; controle?: object } = {},
): void {
  creneauxApi.diagnostic.mockImplementation(async () =>
    reponseApi('/creneaux/diagnostic', autres, surcharges),
  );
  creneauxApi.control.mockImplementation(async () =>
    reponseApi('/creneaux/controle', autres, surcharges),
  );
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  columns: string[];
  sort: WritableSignal<Sort>;
  creneauxAffiches: Signal<Creneau[]>;
  selection: TableSelection<number>;
  causeParCreneau: Signal<Map<number, CauseInfaisabilite>>;
  editingLocked: Signal<boolean>;
  remove: (creneau: Creneau) => Promise<void>;
  removeSelection: () => Promise<void>;
  editSelection: () => void;
  openSerie: () => void;
  openDerivation: () => void;
};

describe('CreneauxPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0),
    reportError: vi.fn(),
  };
  const creneauxApi = {
    diagnostic: vi.fn(),
    control: vi.fn(),
  };
  const confirm = { ask: vi.fn() };
  const notifications = { notify: vi.fn() };
  const resolution = { reload: vi.fn(async () => undefined) };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
  const causeParCreneauId = signal(new Map<string, CauseInfaisabilite>());

  beforeEach(() => {
    for (const stub of [
      crud.reload,
      crud.remove,
      crud.removeMany,
      crud.reportError,
      resolution.reload,
    ]) {
      stub.mockClear();
    }
    for (const stub of Object.values(creneauxApi)) {
      stub.mockReset();
    }
    confirm.ask.mockReset();
    notifications.notify.mockReset();
    dialog.open.mockClear();
    causeParCreneauId.set(new Map());
    confirm.ask.mockResolvedValue(true);
    brancher(creneauxApi);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: CreneauxApi, useValue: creneauxApi },
        {
          provide: ConsignesStore,
          useValue: {
            reload: vi.fn(async () => undefined),
            etat: () => null,
            consignes: () => [],
            aujourdhui: () => null,
            parDate: () => new Map(),
            creneauxAjoutes: () => new Set(),
            consigneDe: () => null,
          },
        },
        // The day-templates card reads its own state; an empty one keeps it quiet here.
        {
          provide: JourneesTypesApi,
          useValue: {
            etat: vi.fn(async () => ({
              journeesTypes: [],
              calendrier: [],
              datesEnEcart: [],
              datesSousConsigne: [],
            })),
          },
        },
        { provide: ReferenceCrudService, useValue: crud },
        {
          provide: SolverJobService,
          useValue: { solverBusy: () => false, editingLocked: () => false },
        },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: notifications },
        { provide: PlanningResolutionStore, useValue: resolution },
        {
          provide: ProblemesStore,
          useValue: { reloadFeasibility: vi.fn(async () => undefined), causeParCreneauId },
        },
      ],
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(creneaux: Creneau[] = []): PageInternals {
    seedStore(referenceData, 'creneaux', creneaux);
    return TestBed.createComponent(CreneauxPage).componentInstance as unknown as PageInternals;
  }

  /** `?edit=<id>` (issue #489): a cause names a créneau, and its link lands here with the form open. */
  describe('the edit deep link', () => {
    it('opens the form of the créneau named in the URL once the référentiel is in', async () => {
      // The page reads the snapshot of the route it is created under: navigate first, create after.
      await TestBed.inject(Router).navigateByUrl('/?edit=12');
      createPage([creneau({ id: 12, jour: 1 }), creneau({ id: 13, jour: 1 })]);
      await Promise.resolve();

      expect(dialog.open).toHaveBeenCalledOnce();
      const [, config] = dialog.open.mock.calls[0] as unknown as [
        unknown,
        { data: { creneau: Creneau } },
      ];
      expect(config.data.creneau.id).toBe(12);
    });

    it('opens nothing for a créneau the référentiel does not hold', async () => {
      await TestBed.inject(Router).navigateByUrl('/?edit=99');
      createPage([creneau({ id: 12, jour: 1 })]);
      await Promise.resolve();

      expect(dialog.open).not.toHaveBeenCalled();
    });
  });

  it('loads the referential and the diagnostic on entry', () => {
    createPage();

    expect(crud.reload).toHaveBeenCalledOnce();
  });

  describe('sorting', () => {
    // `store.creneaux()` is already chronological, and the sort is stable, so
    // an unsorted view must not reorder anything.
    it('keeps the store order while no sort is applied', () => {
      const page = createPage([creneau({ id: 9, jour: 3 }), creneau({ id: 1, jour: 1 })]);

      expect(page.creneauxAffiches().map((row) => row.id)).toEqual([9, 1]);
    });

    it('sorts chronologically by day then start time, and reverses on descending', () => {
      const page = createPage([
        creneau({ id: 3, jour: 2, heureDebut: '09:00' }),
        creneau({ id: 1, jour: 1, heureDebut: '14:00' }),
        creneau({ id: 2, jour: 1, heureDebut: '09:00' }),
      ]);

      page.sort.set({ active: 'jour', direction: 'asc' });
      expect(page.creneauxAffiches().map((row) => row.id)).toEqual([2, 1, 3]);

      page.sort.set({ active: 'jour', direction: 'desc' });
      expect(page.creneauxAffiches().map((row) => row.id)).toEqual([3, 1, 2]);
    });

    // The worst slots must be groupable, which is why this column sorts on the
    // shortfall and not on the presence of a badge.
    it('sorts the problem column on the shortfall, worst last on ascending', () => {
      const page = createPage([
        creneau({ id: 1, jour: 1 }),
        creneau({ id: 2, jour: 2 }),
        creneau({ id: 3, jour: 3 }),
      ]);
      causeParCreneauId.set(
        new Map([
          ['1', cause(2)],
          ['3', cause(5)],
        ]),
      );

      page.sort.set({ active: 'probleme', direction: 'asc' });

      expect(page.creneauxAffiches().map((row) => row.id)).toEqual([2, 1, 3]);
    });

    it('leaves the store untouched while sorting', () => {
      const page = createPage([creneau({ id: 9, jour: 3 }), creneau({ id: 1, jour: 1 })]);

      page.sort.set({ active: 'jour', direction: 'asc' });
      page.creneauxAffiches();

      expect(referenceData.creneaux().map((row) => row.id)).toEqual([9, 1]);
    });
  });

  describe('the feasibility index', () => {
    // The report carries `creneauId` as a string while `Creneau.id` is a
    // number: the normalisation happens once, here, not in every template.
    it('re-keys the report causes on the numeric slot id', () => {
      const page = createPage([creneau({ id: 12, jour: 1 })]);
      causeParCreneauId.set(new Map([['12', cause(3)]]));

      expect(page.causeParCreneau().get(12)?.manque).toBe(3);
    });

    it('holds nothing while no diagnostic has been loaded', () => {
      const page = createPage([creneau({ id: 12, jour: 1 })]);

      expect(page.causeParCreneau().size).toBe(0);
    });

    it('ignores a cause naming a slot the referential no longer holds', () => {
      const page = createPage([creneau({ id: 12, jour: 1 })]);
      causeParCreneauId.set(new Map([['99', cause(3)]]));

      expect(page.causeParCreneau().size).toBe(0);
    });
  });

  describe('multi-selection', () => {
    it('deletes exactly the ticked rows', async () => {
      const page = createPage([
        creneau({ id: 1, jour: 1 }),
        creneau({ id: 2, jour: 2 }),
        creneau({ id: 3, jour: 3 }),
      ]);

      page.selection.toggle(1);
      page.selection.toggle(3);
      await page.removeSelection();

      expect(crud.removeMany).toHaveBeenCalledWith('creneaux', [1, 3], expect.anything());
    });

    it('forgets a slot deleted in the meantime', () => {
      const page = createPage([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2 })]);
      page.selection.toggleAll();

      seedStore(referenceData, 'creneaux', [creneau({ id: 2, jour: 2 })]);

      expect(page.selection.selectedIds()).toEqual([2]);
    });

    // The selection is keyed on the displayed rows, not on the store: it
    // therefore reports them in the order the table shows. On the other
    // reference pages the same keying is what makes "tout sélectionner" follow
    // the quick filter; this page has no filter left since the créneau groups
    // were dropped, so the display order is all that is observable of it.
    it('reports the selection in the displayed order, not the store order', () => {
      const page = createPage([
        creneau({ id: 1, jour: 1 }),
        creneau({ id: 2, jour: 2 }),
        creneau({ id: 3, jour: 3 }),
      ]);
      page.sort.set({ active: 'jour', direction: 'desc' });

      page.selection.toggleAll();

      expect(page.selection.selectedIds()).toEqual([3, 2, 1]);
      expect(referenceData.creneaux().map((row) => row.id)).toEqual([1, 2, 3]);
    });

    it('hands the bulk-edit dialog exactly the selected slots', () => {
      const page = createPage([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2 })]);

      page.selection.toggle(2);
      page.editSelection();

      expect(dialog.open).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({ data: { creneaux: [expect.objectContaining({ id: 2 })] } }),
      );
    });

    it('deletes a single row on its own, without touching the selection', async () => {
      const page = createPage([creneau({ id: 1, jour: 1 })]);

      await page.remove(creneau({ id: 1, jour: 1 }));

      expect(crud.remove).toHaveBeenCalledWith('creneaux', 1, expect.anything());
      expect(crud.removeMany).not.toHaveBeenCalled();
    });
  });

  it('exposes the editing lock as the job service sees it, not as its own copy', () => {
    const page = createPage();

    expect(page.editingLocked()).toBe(false);
  });
});

describe('CreneauxPage rendering', () => {
  let referenceData: ReferenceDataStore;
  let fixture: ComponentFixture<CreneauxPage>;
  const causeParCreneauId = signal(new Map<string, CauseInfaisabilite>());
  const editingLocked = signal(false);
  const creneauxApi = {
    diagnostic: vi.fn(),
    control: vi.fn(),
  };
  const confirm = { ask: vi.fn(async () => false) };
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0),
    reportError: vi.fn(),
  };

  async function rendre(creneaux: Creneau[]): Promise<void> {
    seedStore(referenceData, 'creneaux', creneaux);
    fixture = TestBed.createComponent(CreneauxPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function lignes(): string[][] {
    return Array.from(racine().querySelectorAll('tbody tr')).map((row) =>
      Array.from(row.querySelectorAll('td')).map((cell) =>
        cell.textContent!.replace(/\s+/g, ' ').trim(),
      ),
    );
  }

  function bouton(libelle: string): HTMLButtonElement {
    const trouve = Array.from(racine().querySelectorAll('button')).find((each) =>
      each.textContent!.includes(libelle),
    );
    expect(trouve, `bouton « ${libelle} » absent`).toBeDefined();
    return trouve as HTMLButtonElement;
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    editingLocked.set(false);
    causeParCreneauId.set(new Map());
    brancher(creneauxApi);
    confirm.ask.mockClear();
    confirm.ask.mockResolvedValue(false);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: CreneauxApi, useValue: creneauxApi },
        {
          provide: ConsignesStore,
          useValue: {
            reload: vi.fn(async () => undefined),
            etat: () => null,
            consignes: () => [],
            aujourdhui: () => null,
            parDate: () => new Map(),
            creneauxAjoutes: () => new Set(),
            consigneDe: () => null,
          },
        },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked } },
        {
          provide: MatDialog,
          useValue: { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) },
        },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn(async () => undefined) } },
        {
          provide: ProblemesStore,
          useValue: { reloadFeasibility: vi.fn(async () => undefined), causeParCreneauId },
        },
      ],
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  it('renders one row per créneau, with its event day and hours', async () => {
    await rendre([
      creneau({ id: 1, jour: 1 }),
      creneau({ id: 2, jour: 2, heureDebut: '14:00', heureFin: '19:00' }),
    ]);

    expect(racine().querySelector('.creneaux-liste-card h2')!.textContent!).toContain(
      'Créneaux (2)',
    );
    expect(lignes().map((row) => [row[1], row[3]])).toEqual([
      ['J1', '10:00–12:00'],
      ['J2', '14:00–19:00'],
    ]);
  });

  it('names the shortfall in the problem column, and says « Aucun » for the others', async () => {
    causeParCreneauId.set(new Map([['1', cause(2)]]));
    await rendre([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 1 })]);

    expect(lignes()[0].at(-2)).toContain('2 animateur(s) manquant(s)');
    // Not an empty cell: a screen reader reading the row must hear something.
    expect(lignes()[1].at(-2)).toBe('Aucun');
  });

  it('says the referential is empty rather than showing a bare table', async () => {
    await rendre([]);

    expect(racine().querySelector('.creneaux-liste-card .empty-hint')!.textContent!.trim()).toBe(
      'Aucun créneau pour le moment.',
    );
  });

  it('locks the writing actions, and says why, while a solve is running', async () => {
    await rendre([creneau({ id: 1, jour: 1 })]);
    editingLocked.set(true);
    await fixture.whenStable();

    expect(racine().querySelector('.locked-hint')).not.toBeNull();
    const actions = racine().querySelectorAll('tbody .row-actions button');
    expect(Array.from(actions).every((each) => (each as HTMLButtonElement).disabled)).toBe(true);
    // The derivation can replace the whole grid: locked with the rest.
    expect(bouton('Dériver des horaires des stands').disabled).toBe(true);
  });

  describe('the grid as a whole', () => {
    /** The page's own reads are plain promises the zoneless fixture does not track: let them settle. */
    async function rendreEtLire(creneaux: Creneau[]): Promise<void> {
      await rendre(creneaux);
      await new Promise((resolve) => setTimeout(resolve, 0));
      await fixture.whenStable();
    }

    function text(selecteur: string): string {
      return racine().querySelector(selecteur)!.textContent!.replace(/\s+/g, ' ').trim();
    }

    it('shows the verdict on the page, errors first, and the stand openings apart', async () => {
      brancher(creneauxApi, [], {
        controle: {
          anomalies: [
            {
              severite: 'AVERTISSEMENT',
              type: 'TROU_DANS_LA_JOURNEE',
              date: '2026-08-01',
              message: 'Trou de 12:00 à 14:00',
            },
            {
              severite: 'ERREUR',
              type: 'DOUBLON',
              date: '2026-08-01',
              message: 'Doublon 10:00-12:00',
            },
          ],
          ouvertures: [
            {
              type: 'STAND_JAMAIS_OUVERT',
              standId: 'S1',
              standNom: 'Stand un',
              date: null,
              message: 'Jamais ouvert',
            },
          ],
          faisabilite: {
            feasible: false,
            manqueAnimateurs: 2,
            causes: [],
            totalCauses: 1,
            causesCritiques: 0,
            causesElevees: 0,
            message: 'Il manque 2 animateurs.',
          },
        },
      });
      await rendreEtLire([creneau({ id: 1, jour: 1 })]);

      const messages = Array.from(racine().querySelectorAll('.controle-anomalies li')).map((each) =>
        each.textContent!.replace(/\s+/g, ' ').trim(),
      );
      expect(messages[0]).toContain('Doublon');
      expect(messages[1]).toContain('Trou');
      expect(messages[2]).toContain('Stand un');
      expect(text('.controle-bilan')).toContain('1 erreur(s)');
      expect(text('.controle-bilan')).toContain('Il manque 2 animateurs.');
    });

    it('says so when there is nothing to report', async () => {
      await rendreEtLire([creneau({ id: 1, jour: 1 })]);
      expect(racine().textContent).toContain('Rien à signaler');
    });

    it('opens the série dialog with the current verdict, locked with the rest', async () => {
      await rendreEtLire([creneau({ id: 1, jour: 1 })]);
      const dialog = TestBed.inject(MatDialog) as unknown as { open: ReturnType<typeof vi.fn> };

      bouton('Créer une série').click();
      expect(dialog.open).toHaveBeenCalledOnce();
      expect((dialog.open.mock.calls[0] as unknown as [unknown, { data: object }])[1].data).toEqual(
        {
          controleActuel: { ...CONTROLE },
        },
      );

      editingLocked.set(true);
      await fixture.whenStable();
      expect(bouton('Créer une série').disabled).toBe(true);
    });

    it('opens the derivation dialog prefilled with the span of the grid', async () => {
      await rendreEtLire([
        creneau({ id: 2, jour: 2, date: '2026-08-03' }),
        creneau({ id: 1, jour: 1, date: '2026-08-01' }),
      ]);
      const dialog = TestBed.inject(MatDialog) as unknown as { open: ReturnType<typeof vi.fn> };

      bouton('Dériver des horaires des stands').click();

      expect(dialog.open).toHaveBeenCalledOnce();
      expect((dialog.open.mock.calls[0] as unknown as [unknown, { data: object }])[1].data).toEqual(
        {
          dateDebut: '2026-08-01',
          dateFin: '2026-08-03',
        },
      );
    });
  });
});

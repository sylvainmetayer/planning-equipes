// Sorting, the conditional « Famille » column, multi-selection, the feasibility
// index and the auto-slicing round-trip of the timeslots table. The first half
// creates the component without rendering it; the second one renders the page,
// because the slicing card is a destructive action (it replaces every créneau
// and wipes the solved planning) and what guards it — a confirmation, a
// disabled button while it runs — only exists in the template.
//
// Written as the safety net the `<app-reference-table>` extraction needs.
// `decoupage.spec.ts` owns the per-day summary; what is pinned here is the
// page that drives it.

import { provideZonelessChangeDetection, Signal, WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { Sort } from '@angular/material/sort';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
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
    manque
  };
}

const PARAMETRES_DECOUPAGE = {
  dureeVacationCibleMinutes: 300,
  dureeVacationMinMinutes: 180,
  dureeVacationMaxMinutes: 360,
  dureeChevauchementMinutes: 30,
  dureePauseRepasMinutes: 45,
  fenetreRepasMidiDebut: '12:00',
  fenetreRepasMidiFin: '14:00',
  fenetreRepasSoirDebut: '19:00',
  fenetreRepasSoirFin: '21:00',
  strategieCouverturePendantPause: 'FERMETURE',
  modeGrille: 'AMPLITUDES',
  nombreFamillesDecalage: 1,
  dureeDecalageMaxMinutes: 0
};

const DIAGNOSTIC = {
  nombreCreneaux: 1,
  premiereDate: '2026-08-01',
  derniereDate: '2026-08-01',
  nombreFamilles: 1,
  contientCouverturePause: false,
  modeProbable: 'AMPLITUDES',
  modeCertain: false,
  explication: 'Durée médiane de 120 min : rien ne le prouve.'
};

const CONTROLE = {
  mode: 'AMPLITUDES',
  nombreCreneaux: 1,
  anomalies: [] as { severite: string; type: string; date: string | null; message: string }[],
  ouvertures: [] as unknown[],
  faisabilite: null
};

/**
 * The page reads four endpoints on entry: the créneaux list is the store's
 * business, the three others answer here by URL, with `autres` for whatever
 * a test wants the remaining calls (the découpage preview) to return.
 */
function reponseApi(url: string, autres: unknown = [], surcharges: { parametres?: object; diagnostic?: object; controle?: object } = {}): unknown {
  if (url.includes('parametres-decoupage')) {
    return { ...PARAMETRES_DECOUPAGE, ...(surcharges.parametres ?? {}) };
  }
  if (url.includes('/creneaux/diagnostic')) {
    return { ...DIAGNOSTIC, ...(surcharges.diagnostic ?? {}) };
  }
  if (url.includes('/creneaux/controle')) {
    return { ...CONTROLE, ...(surcharges.controle ?? {}) };
  }
  return autres;
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  columns: Signal<string[]>;
  afficherFamilles: Signal<boolean>;
  sort: WritableSignal<Sort>;
  creneauxAffiches: Signal<Creneau[]>;
  selection: TableSelection<number>;
  causeParCreneau: Signal<Map<number, CauseInfaisabilite>>;
  editingLocked: Signal<boolean>;
  previewVacations: Signal<Creneau[] | null>;
  previewLoading: Signal<boolean>;
  genererLoading: Signal<boolean>;
  resumeDecoupage: Signal<unknown[]>;
  previsualiserDecoupage: () => Promise<void>;
  genererDecoupage: () => Promise<void>;
  remove: (creneau: Creneau) => Promise<void>;
  removeSelection: () => Promise<void>;
  editSelection: () => void;
  mode: Signal<string>;
  changerMode: (mode: string) => Promise<void>;
  openSerie: () => void;
};

describe('CreneauxPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0),
    reportError: vi.fn()
  };
  const api = { get: vi.fn(), post: vi.fn(), put: vi.fn() };
  const confirm = { ask: vi.fn() };
  const notifications = { notify: vi.fn() };
  const resolution = { reload: vi.fn(async () => undefined) };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => ({ subscribe: vi.fn() }) })) };
  const causeParCreneauId = signal(new Map<string, CauseInfaisabilite>());

  beforeEach(() => {
    for (const stub of [crud.reload, crud.remove, crud.removeMany, crud.reportError, resolution.reload]) {
      stub.mockClear();
    }
    api.get.mockReset();
    api.post.mockReset();
    api.put.mockReset();
    confirm.ask.mockReset();
    notifications.notify.mockReset();
    dialog.open.mockClear();
    causeParCreneauId.set(new Map());
    confirm.ask.mockResolvedValue(true);
    api.get.mockImplementation(async (url: string) => reponseApi(url));
    api.post.mockResolvedValue(undefined);
    api.put.mockImplementation(async (_url: string, corps: object) => corps);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked: () => false } },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: notifications },
        { provide: PlanningResolutionStore, useValue: resolution },
        {
          provide: ProblemesStore,
          useValue: { reloadFeasibility: vi.fn(async () => undefined), causeParCreneauId }
        }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(creneaux: Creneau[] = []): PageInternals {
    referenceData.creneaux.set(creneaux);
    return TestBed.createComponent(CreneauxPage).componentInstance as unknown as PageInternals;
  }

  it('loads the referential and the diagnostic on entry', () => {
    createPage();

    expect(crud.reload).toHaveBeenCalledOnce();
  });

  describe('the conditional « Famille » column', () => {
    // A group generated with N stagger families holds N same-looking variants
    // of every slot; without the column they read as inexplicable duplicates,
    // and with it they are noise on the groups that have a single family.
    it('hides the column when no displayed slot carries a family', () => {
      const page = createPage([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2, famille: 0 })]);

      expect(page.afficherFamilles()).toBe(false);
      expect(page.columns()).not.toContain('famille');
    });

    it('shows the column as soon as one displayed slot carries a family', () => {
      const page = createPage([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2, famille: 1 })]);

      expect(page.afficherFamilles()).toBe(true);
      expect(page.columns()).toContain('famille');
    });

    it('keeps the checkbox and actions columns around the data ones either way', () => {
      const page = createPage([creneau({ id: 1, jour: 1, famille: 2 })]);

      expect(page.columns()[0]).toBe('select');
      expect(page.columns().at(-1)).toBe('actions');
    });
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
        creneau({ id: 2, jour: 1, heureDebut: '09:00' })
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
        creneau({ id: 3, jour: 3 })
      ]);
      causeParCreneauId.set(
        new Map([
          ['1', cause(2)],
          ['3', cause(5)]
        ])
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
      const page = createPage([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2 }), creneau({ id: 3, jour: 3 })]);

      page.selection.toggle(1);
      page.selection.toggle(3);
      await page.removeSelection();

      expect(crud.removeMany).toHaveBeenCalledWith('creneaux', [1, 3], expect.anything());
    });

    it('forgets a slot deleted in the meantime', () => {
      const page = createPage([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2 })]);
      page.selection.toggleAll();

      referenceData.creneaux.set([creneau({ id: 2, jour: 2 })]);

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
        creneau({ id: 3, jour: 3 })
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
        expect.objectContaining({ data: { creneaux: [expect.objectContaining({ id: 2 })] } })
      );
    });

    it('deletes a single row on its own, without touching the selection', async () => {
      const page = createPage([creneau({ id: 1, jour: 1 })]);

      await page.remove(creneau({ id: 1, jour: 1 }));

      expect(crud.remove).toHaveBeenCalledWith('creneaux', 1, expect.anything());
      expect(crud.removeMany).not.toHaveBeenCalled();
    });
  });

  describe('previewing the auto-slicing', () => {
    it('drops the previous preview before asking for a new one', async () => {
      const page = createPage();
      api.get.mockResolvedValue([creneau({ id: 1, jour: 1 })]);
      await page.previsualiserDecoupage();
      expect(page.previewVacations()).toHaveLength(1);

      api.get.mockRejectedValue(new Error('Découpage impossible.'));
      await page.previsualiserDecoupage();

      expect(page.previewVacations()).toBeNull();
      expect(page.resumeDecoupage()).toEqual([]);
      expect(crud.reportError).toHaveBeenCalledOnce();
    });

    it('summarises the previewed vacations by day', async () => {
      const page = createPage();
      api.get.mockResolvedValue([
        creneau({ id: 1, jour: 1, date: '2026-08-01' }),
        creneau({ id: 2, jour: 1, date: '2026-08-01' }),
        creneau({ id: 3, jour: 2, date: '2026-08-02' })
      ]);

      await page.previsualiserDecoupage();

      expect(api.get).toHaveBeenCalledWith('/api/decoupage/preview');
      expect(page.resumeDecoupage()).toHaveLength(2);
      expect(page.previewLoading()).toBe(false);
    });

    it('lowers the in-flight flag even when the preview fails', async () => {
      const page = createPage();
      api.get.mockRejectedValue(new Error('Découpage impossible.'));

      await page.previsualiserDecoupage();

      expect(page.previewLoading()).toBe(false);
    });
  });

  describe('generating the auto-slicing', () => {
    // The generation replaces the edition's créneaux and erases the persisted
    // plan with them: it is confirmed first, and flagged as dangerous.
    it('writes nothing when the confirmation is refused', async () => {
      const page = createPage();
      confirm.ask.mockResolvedValue(false);

      await page.genererDecoupage();

      expect(confirm.ask).toHaveBeenCalledWith(expect.objectContaining({ danger: true }));
      expect(api.post).not.toHaveBeenCalled();
    });

    it('generates, then reloads both the referential and the resolution state', async () => {
      const page = createPage();
      crud.reload.mockClear();

      await page.genererDecoupage();

      expect(api.post).toHaveBeenCalledExactlyOnceWith('/api/decoupage/generer', {});
      expect(crud.reload).toHaveBeenCalledOnce();
      expect(resolution.reload).toHaveBeenCalledOnce();
      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
    });

    it('reports a failed generation and reloads nothing', async () => {
      const page = createPage();
      api.post.mockRejectedValue(new Error('Découpage refusé.'));
      crud.reload.mockClear();

      await page.genererDecoupage();

      expect(crud.reportError).toHaveBeenCalledOnce();
      expect(crud.reload).not.toHaveBeenCalled();
      expect(notifications.notify).not.toHaveBeenCalled();
      expect(page.genererLoading()).toBe(false);
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
  const api = {
    get: vi.fn(async (url: string): Promise<unknown> => reponseApi(url)),
    post: vi.fn(async () => undefined),
    put: vi.fn(async (_url: string, corps: object) => ({ ...PARAMETRES_DECOUPAGE, ...corps }))
  };
  const confirm = { ask: vi.fn(async () => false) };
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0),
    reportError: vi.fn()
  };

  async function rendre(creneaux: Creneau[]): Promise<void> {
    referenceData.creneaux.set(creneaux);
    fixture = TestBed.createComponent(CreneauxPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function entetes(): string[] {
    return Array.from(racine().querySelectorAll('thead th')).map((each) => each.textContent!.trim());
  }

  function lignes(): string[][] {
    return Array.from(racine().querySelectorAll('tbody tr')).map((row) =>
      Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent!.replace(/\s+/g, ' ').trim())
    );
  }

  function bouton(libelle: string): HTMLButtonElement {
    const trouve = Array.from(racine().querySelectorAll('button')).find((each) =>
      each.textContent!.includes(libelle)
    );
    expect(trouve, `bouton « ${libelle} » absent`).toBeDefined();
    return trouve as HTMLButtonElement;
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    editingLocked.set(false);
    causeParCreneauId.set(new Map());
    api.get.mockReset();
    api.get.mockImplementation(async (url: string) => reponseApi(url));
    api.post.mockClear();
    api.put.mockClear();
    confirm.ask.mockClear();
    confirm.ask.mockResolvedValue(false);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked } },
        { provide: MatDialog, useValue: { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) } },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: PlanningResolutionStore, useValue: { reload: vi.fn(async () => undefined) } },
        {
          provide: ProblemesStore,
          useValue: { reloadFeasibility: vi.fn(async () => undefined), causeParCreneauId }
        }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  it('renders one row per créneau, with its event day and hours', async () => {
    await rendre([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 2, heureDebut: '14:00', heureFin: '19:00' })]);

    expect(racine().querySelector('h2')!.textContent!).toContain('Créneaux (2)');
    expect(lignes().map((row) => [row[1], row[3]])).toEqual([
      ['J1', '10:00–12:00'],
      ['J2', '14:00–19:00']
    ]);
  });

  it('hides the « Famille » column while every créneau belongs to the first family', async () => {
    await rendre([creneau({ id: 1, jour: 1 })]);
    expect(entetes()).not.toContain('Famille');

    await rendre([creneau({ id: 1, jour: 1 }), creneau({ id: 2, jour: 1, famille: 1 })]);
    // Two identical hours in different families are not duplicates: the column
    // is what says so, and it only appears when there is something to say.
    expect(entetes()).toContain('Famille');
    expect(lignes().map((row) => row[4])).toEqual(['1', '2']);
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

    expect(racine().querySelector('.empty-hint')!.textContent!.trim()).toBe('Aucun créneau pour le moment.');
  });

  it('locks the writing actions, and says why, while a solve is running', async () => {
    await rendre([creneau({ id: 1, jour: 1 })]);
    editingLocked.set(true);
    await fixture.whenStable();

    expect(racine().querySelector('.locked-hint')).not.toBeNull();
    const actions = racine().querySelectorAll('tbody .row-actions button');
    expect(Array.from(actions).every((each) => (each as HTMLButtonElement).disabled)).toBe(true);
    // Generating the slicing replaces every créneau: locked with the rest.
    expect(bouton('Générer les vacations').disabled).toBe(true);
  });

  it('previews the slicing without writing anything', async () => {
    api.get.mockImplementation(async (url: string) =>
      reponseApi(url, [
        { id: 1, jour: 1, date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00' },
        { id: 2, jour: 1, date: '2026-08-01', heureDebut: '12:00', heureFin: '14:00' }
      ])
    );
    await rendre([creneau({ id: 1, jour: 1 })]);

    bouton('Prévisualiser').click();
    await fixture.whenStable();

    expect(api.post).not.toHaveBeenCalled();
    const resume = racine().querySelector('.decoupage-resume')!;
    expect(resume.textContent!).toContain('2026-08-01');
    expect(Array.from(resume.querySelectorAll('.vacation-chip')).map((each) => each.textContent!.trim())).toEqual([
      '10:00–12:00',
      '12:00–14:00'
    ]);
  });

  it('never generates the slicing without an explicit confirmation', async () => {
    await rendre([creneau({ id: 1, jour: 1 })]);

    bouton('Générer les vacations').click();
    await fixture.whenStable();

    // It replaces every créneau of the edition and wipes the solved planning.
    expect(api.post).not.toHaveBeenCalled();

    confirm.ask.mockResolvedValue(true);
    bouton('Générer les vacations').click();
    await fixture.whenStable();
    expect(api.post).toHaveBeenCalledWith('/api/decoupage/generer', {});
  });

  it('points at the Paramètres page for the slicing settings', async () => {
    await rendre([creneau({ id: 1, jour: 1 })]);

    const lien = Array.from(racine().querySelectorAll('a')).find((each) =>
      each.textContent!.includes('découpage')
    )!;
    expect(lien.getAttribute('href')).toBe('/parametres');
  });

  describe('the grid as a whole', () => {
    /** The page's own reads are plain promises the zoneless fixture does not track: let them settle. */
    async function rendreEtLire(creneaux: Creneau[]): Promise<void> {
      await rendre(creneaux);
      await new Promise((resolve) => setTimeout(resolve, 0));
      await fixture.whenStable();
    }

    function texte(selecteur: string): string {
      return racine().querySelector(selecteur)!.textContent!.replace(/\s+/g, ' ').trim();
    }

    it('declares the mode from the découpage settings and writes it back through them, then rereads the verdict', async () => {
      await rendreEtLire([creneau({ id: 1, jour: 1 })]);
      const page = fixture.componentInstance as unknown as PageInternals;
      expect(page.mode()).toBe('AMPLITUDES');
      const lectures = api.get.mock.calls.filter(([url]) => String(url).includes('/creneaux/controle')).length;

      await page.changerMode('VACATIONS');
      await fixture.whenStable();

      // One write, carrying the whole settings object with only the mode changed —
      // never a write on entry, which would reset the mode to its default.
      expect(api.put).toHaveBeenCalledOnce();
      // Its own endpoint: sending the whole settings object would let a stale
      // Paramètres tab revert this choice.
      expect(api.put).toHaveBeenCalledWith('/api/parametres-decoupage/mode-grille', { modeGrille: 'VACATIONS' });
      expect(page.mode()).toBe('VACATIONS');
      expect(api.get.mock.calls.filter(([url]) => String(url).includes('/creneaux/controle')).length).toBe(lectures + 1);
      // Nothing to slice on a grid of final vacations.
      expect(bouton('Générer les vacations').disabled).toBe(true);
      expect(racine().textContent).toContain("rien à découper");
    });

    it('says when the data proves a mode the declaration contradicts', async () => {
      api.get.mockImplementation(async (url: string) =>
        reponseApi(url, [], { diagnostic: { modeProbable: 'VACATIONS', modeCertain: true, explication: 'Grille déjà découpée : 3 familles.' } })
      );
      await rendreEtLire([creneau({ id: 1, jour: 1 })]);

      const diagnostic = racine().querySelector('.grille-diagnostic')!;
      expect(diagnostic.classList.contains('grille-diagnostic-desaccord')).toBe(true);
      expect(diagnostic.textContent).toContain('3 familles');
    });

    it('shows the verdict on the page, errors first, and the stand openings apart', async () => {
      api.get.mockImplementation(async (url: string) =>
        reponseApi(url, [], {
          controle: {
            anomalies: [
              { severite: 'AVERTISSEMENT', type: 'TROU_DANS_LA_JOURNEE', date: '2026-08-01', message: 'Trou de 12:00 à 14:00' },
              { severite: 'ERREUR', type: 'DOUBLON', date: '2026-08-01', message: 'Doublon 10:00-12:00' }
            ],
            ouvertures: [{ type: 'STAND_JAMAIS_OUVERT', standId: 'S1', standNom: 'Stand un', date: null, message: 'Jamais ouvert' }],
            faisabilite: { feasible: false, manqueAnimateurs: 2, causes: [], totalCauses: 1, message: 'Il manque 2 animateurs.' }
          }
        })
      );
      await rendreEtLire([creneau({ id: 1, jour: 1 })]);

      const messages = Array.from(racine().querySelectorAll('.controle-anomalies li')).map((each) => each.textContent!.replace(/\s+/g, ' ').trim());
      expect(messages[0]).toContain('Doublon');
      expect(messages[1]).toContain('Trou');
      expect(messages[2]).toContain('Stand un');
      expect(texte('.controle-bilan')).toContain('1 erreur(s)');
      expect(texte('.controle-bilan')).toContain('Il manque 2 animateurs.');
    });

    it('says so when there is nothing to report', async () => {
      await rendreEtLire([creneau({ id: 1, jour: 1 })]);
      expect(racine().textContent).toContain('Rien à signaler');
    });

    it('opens the série dialog with the declared mode, locked with the rest', async () => {
      await rendreEtLire([creneau({ id: 1, jour: 1 })]);
      const dialog = TestBed.inject(MatDialog) as unknown as { open: ReturnType<typeof vi.fn> };

      bouton('Créer une série').click();
      expect(dialog.open).toHaveBeenCalledOnce();
      expect((dialog.open.mock.calls[0] as unknown as [unknown, { data: { mode: string } }])[1].data).toEqual({
        mode: 'AMPLITUDES',
        controleActuel: { ...CONTROLE }
      });

      editingLocked.set(true);
      await fixture.whenStable();
      expect(bouton('Créer une série').disabled).toBe(true);
    });

    it('declares the grid as vacations once the découpage has generated them', async () => {
      confirm.ask.mockResolvedValue(true);
      await rendreEtLire([creneau({ id: 1, jour: 1 })]);

      bouton('Générer les vacations').click();
      await new Promise((resolve) => setTimeout(resolve, 0));
      await fixture.whenStable();

      expect(api.post).toHaveBeenCalledWith('/api/decoupage/generer', {});
      // The server declares the grid as vacations when it slices it: the page
      // reads the settings back rather than writing the mode itself.
      expect(api.put).not.toHaveBeenCalled();
      expect(api.get.mock.calls.filter(([url]) => String(url).includes('parametres-decoupage')).length).toBeGreaterThan(1);
    });
  });
});

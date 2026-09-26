// Filtering, multi-selection and cell labels of the stands table, plus the
// horaires compaction it alone carries. The first half creates the component
// without rendering it; the second one renders the table, where the claims a
// user acts on live — the summarised horaires column, the shortfall icon and
// what the solver lock really greys out.
//
// Written as the safety net the `<app-reference-table>` extraction needs: what
// is pinned here is the glue the five reference pages repeat, not the CRUD
// service underneath (`reference-crud.service.spec.ts` owns that).

import { provideZonelessChangeDetection, signal, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { StandsApi } from '../../core/api/stands-api';
import { NotificationService } from '../../core/notification.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { ConfirmService } from '../../shared/confirm-dialog';
import { CauseInfaisabilite, Emplacement, EtatGel, Stand } from '../../core/models';
import { StandsPage } from './stands-page';
import { seedStore } from '../../core/testing/seed-store';
import { rowMenuItem } from '../../core/testing/row-menu';
import { expectOnlyInEmptyState } from '../../core/testing/empty-state';

function stand(overrides: Partial<Stand> & { id: string }): Stand {
  return {
    nom: overrides.id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 2,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
    ...overrides,
  };
}

function emplacement(id: string, nom: string): Emplacement {
  return { id, nom, latitude: null, longitude: null };
}

/** One recurring rule holding `fenetres` windows — a rule is one row, not `fenetres` rows. */
/** Reaches the protected members the template binds to. */
type PageInternals = {
  columns: Signal<string[]>;
  filtre: { set: (value: string) => void };
  standsFiltres: Signal<Stand[]>;
  selection: TableSelection<string>;
  compactageEnCours: Signal<boolean>;
  editingLocked: Signal<boolean>;
  typologiesOf: (stand: Stand) => { id: string; label: string }[];
  effectifSuffix: (stand: Stand) => string;
  emplacementLabel: (stand: Stand) => string;
  ouvertLabel: (stand: Stand) => string;
  compacterHoraires: () => Promise<void>;
  remove: (stand: Stand) => Promise<void>;
  removeSelection: () => Promise<void>;
  editSelection: () => void;
  openCreate: () => void;
  edit: (stand: Stand) => void;
};

/** The page reads the snapshot of the route it is created under: navigate first, create after. */
async function arriveWith(edit: string): Promise<void> {
  await TestBed.inject(Router).navigateByUrl(`/?edit=${edit}`);
}

describe('StandsPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0),
    reportError: vi.fn(),
    warningsOf: vi.fn(() => []),
  };
  const api = { get: vi.fn() };
  const standsApi = {
    compactSchedules: vi.fn(),
    openings: vi.fn(async () => ({
      jours: [],
      stands: [],
      standsJamaisOuverts: 0,
      postesTotal: 0,
      anomalies: [],
    })),
  };
  const confirm = { ask: vi.fn() };
  const notifications = { notify: vi.fn() };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => ({ subscribe: vi.fn() }) })) };

  beforeEach(() => {
    for (const stub of [crud.reload, crud.remove, crud.removeMany, crud.reportError]) {
      stub.mockClear();
    }
    api.get.mockReset();
    standsApi.compactSchedules.mockReset();
    confirm.ask.mockReset();
    notifications.notify.mockReset();
    dialog.open.mockClear();
    api.get.mockResolvedValue({
      feasible: true,
      manqueAnimateurs: 0,
      causes: [],
      totalCauses: 0,
      causesCritiques: 0,
      causesElevees: 0,
      message: '',
    });
    confirm.ask.mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: api },
        { provide: StandsApi, useValue: standsApi },
        { provide: ReferenceCrudService, useValue: crud },
        {
          provide: SolverJobService,
          useValue: { solverBusy: () => false, editingLocked: () => false },
        },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: notifications },
        {
          provide: ProblemesStore,
          useValue: {
            reloadFeasibility: vi.fn(async () => undefined),
            causeParStandId: () => new Map(),
          },
        },
      ],
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(stands: Stand[] = []): PageInternals {
    seedStore(referenceData, 'stands', stands);
    return TestBed.createComponent(StandsPage).componentInstance as unknown as PageInternals;
  }

  /**
   * `?edit=<id>` (issue #489): a problem or a warning names a stand. The route
   * sends it to the stand's fiche, which opens its form: the table itself
   * opens nothing — a form opened here as well would be a second one.
   */
  it('leaves the edit deep link to the route, which opens the fiche', async () => {
    await arriveWith('S1');
    createPage([stand({ id: 'S1', nom: 'Escape' })]);
    await Promise.resolve();

    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('leaves the filter and the sort of a link to the Lieux tab to the Lieux table', async () => {
    await TestBed.inject(Router).navigateByUrl('/?onglet=lieux&q=Hall&sort=nom&dir=asc');
    const page = createPage([stand({ id: 'S1', nom: 'Hall des jeux' })]) as unknown as {
      onglet: Signal<string>;
      filtre: Signal<string>;
      sort: Signal<{ active: string; direction: string }>;
    };

    expect(page.onglet()).toBe('lieux');
    // Coming back to the stands shows them all, in the order of their ids.
    expect(page.filtre()).toBe('');
    expect(page.sort().active).toBe('');
  });

  // A location of the « Lieu » column, or the palette's « Stands › Lieux »:
  // the same route with another `onglet`, where the router keeps the page.
  it('follows a navigation to the Lieux tab and back while the page is on screen', async () => {
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/?sort=nom&dir=asc');
    const page = createPage([stand({ id: 'S1', nom: 'Hall des jeux' })]) as unknown as {
      onglet: Signal<string>;
      filtre: Signal<string>;
      sort: Signal<{ active: string; direction: string }>;
      emplacementFiltre: Signal<string>;
    };
    expect(page.onglet()).toBe('stands');
    expect(page.sort().active).toBe('nom');

    await router.navigateByUrl('/?onglet=lieux&q=Hall');
    expect(page.onglet()).toBe('lieux');
    expect(page.filtre()).toBe('');
    expect(page.sort().active).toBe('');

    await router.navigateByUrl('/?emplacement=L1&q=jeux');
    expect(page.onglet()).toBe('stands');
    expect(page.emplacementFiltre()).toBe('L1');
    expect(page.filtre()).toBe('jeux');
  });

  /** `?ids=`: the rows an import just wrote, opened by « Voir les N lignes importées ». */
  describe('the rows an import just wrote', () => {
    afterEach(async () => {
      await TestBed.inject(Router).navigateByUrl('/');
    });

    it('lists them alone, says so, and brings the whole list back on « Tout afficher »', async () => {
      await TestBed.inject(Router).navigateByUrl('/?ids=S1,S3');
      seedStore(referenceData, 'stands', [
        stand({ id: 'S1', nom: 'Escape' }),
        stand({ id: 'S2', nom: 'Cirque' }),
        stand({ id: 'S3', nom: 'Magie' }),
      ]);
      const fixture = TestBed.createComponent(StandsPage);
      const page = fixture.componentInstance as unknown as { standsFiltres: () => Stand[] };
      await fixture.whenStable();
      const element = fixture.nativeElement as HTMLElement;

      expect(page.standsFiltres().map((each) => each.id)).toEqual(['S1', 'S3']);
      expect(element.textContent).toContain('Filtre : lignes importées (2)');

      Array.from(element.querySelectorAll('button'))
        .find((button) => (button.textContent ?? '').includes('Tout afficher'))!
        .click();
      await fixture.whenStable();

      expect(page.standsFiltres()).toHaveLength(3);
      expect(element.textContent).not.toContain('lignes importées');
    });
  });

  it('loads the referential on entry rather than showing whatever the previous page left', () => {
    createPage();

    expect(crud.reload).toHaveBeenCalledOnce();
  });

  describe('quick filter', () => {
    it('shows every stand while the filter is empty', () => {
      const page = createPage([stand({ id: 'tir' }), stand({ id: 'quilles' })]);

      // The ids in their natural order, by default.
      expect(page.standsFiltres().map((row) => row.id)).toEqual(['quilles', 'tir']);
    });

    it('matches on the id and on the name', () => {
      const page = createPage([
        stand({ id: 'tir', nom: 'Tir à la corde' }),
        stand({ id: 'quilles', nom: 'Molkky' }),
      ]);

      page.filtre.set('molkky');
      expect(page.standsFiltres().map((row) => row.id)).toEqual(['quilles']);

      page.filtre.set('tir');
      expect(page.standsFiltres().map((row) => row.id)).toEqual(['tir']);
    });

    it('matches on a proposed typologie', () => {
      const page = createPage([
        stand({ id: 'tir', typologiesProposees: ['AMBIANCE'] }),
        stand({ id: 'quilles', typologiesProposees: ['STRATEGIE'] }),
      ]);

      page.filtre.set('strategie');

      expect(page.standsFiltres().map((row) => row.id)).toEqual(['quilles']);
    });

    it('matches on the emplacement name and id', () => {
      const page = createPage([
        stand({ id: 'tir', emplacement: emplacement('prairie', 'Grande prairie') }),
        stand({ id: 'quilles', emplacement: emplacement('halle', 'Halle') }),
      ]);

      page.filtre.set('prairie');

      expect(page.standsFiltres().map((row) => row.id)).toEqual(['tir']);
    });

    it('shows nothing rather than everything when nothing matches', () => {
      const page = createPage([stand({ id: 'tir' })]);

      page.filtre.set('introuvable');

      expect(page.standsFiltres()).toEqual([]);
    });
  });

  describe('multi-selection', () => {
    it('deletes exactly the ticked rows', async () => {
      const page = createPage([
        stand({ id: 'tir' }),
        stand({ id: 'quilles' }),
        stand({ id: 'palet' }),
      ]);

      page.selection.toggle('tir');
      page.selection.toggle('palet');
      await page.removeSelection();

      expect(crud.removeMany).toHaveBeenCalledWith('stands', ['palet', 'tir'], expect.anything());
    });

    // The referential is reloaded after every write: a row that vanished must
    // stop weighing on the selection.
    it('forgets a stand deleted in the meantime', () => {
      const page = createPage([stand({ id: 'tir' }), stand({ id: 'quilles' })]);
      page.selection.toggleAll();

      seedStore(referenceData, 'stands', [stand({ id: 'quilles' })]);

      expect(page.selection.selectedIds()).toEqual(['quilles']);
    });

    // "Tout sélectionner" must follow what the table shows, not the whole
    // referential, or the filter would become a trap.
    it('narrows select-all to the filtered rows', () => {
      const page = createPage([stand({ id: 'tir' }), stand({ id: 'quilles' })]);

      page.filtre.set('tir');
      page.selection.toggleAll();

      expect(page.selection.selectedIds()).toEqual(['tir']);
    });

    it('hands the bulk-edit dialog exactly the selected stands', () => {
      const page = createPage([stand({ id: 'tir' }), stand({ id: 'quilles' })]);

      page.selection.toggle('quilles');
      page.editSelection();

      expect(dialog.open).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({ data: { stands: [expect.objectContaining({ id: 'quilles' })] } }),
      );
    });

    it('deletes a single row on its own, without touching the selection', async () => {
      const page = createPage([stand({ id: 'tir' })]);

      await page.remove(stand({ id: 'tir', nom: "Tir à l'arc" }));

      expect(crud.remove).toHaveBeenCalledWith('stands', 'tir', expect.anything(), {
        name: { text: "Tir à l'arc" },
      });
      expect(crud.removeMany).not.toHaveBeenCalled();
    });
  });

  describe('cell labels', () => {
    it('renders a dash rather than an empty cell for a stand without typologie or emplacement', () => {
      const page = createPage();

      expect(page.typologiesOf(stand({ id: 'tir' }))).toEqual([]);
      expect(page.emplacementLabel(stand({ id: 'tir' }))).toBe('—');
    });

    it('lists the typologies of a stand by label, comma separated', () => {
      // The ids are generated (T1, T2…) and say nothing: the cell reads the label.
      seedStore(referenceData, 'typologies', [
        { id: 'T1', code: 'AMBIANCE', label: "Jeux d'ambiance", ninja: false },
        { id: 'T2', code: 'STRATEGIE', label: 'Stratégie', ninja: false },
      ]);
      const page = createPage();

      expect(
        page
          .typologiesOf(stand({ id: 'tir', typologiesProposees: ['T1', 'T2'] }))
          .map((typologie) => typologie.label),
      ).toEqual(["Jeux d'ambiance", 'Stratégie']);
    });

    it('finds a stand by the label of one of its typologies', () => {
      seedStore(referenceData, 'typologies', [
        { id: 'T1', code: 'AMBIANCE', label: "Jeux d'ambiance", ninja: false },
      ]);
      const page = createPage([
        stand({ id: 'S1', typologiesProposees: ['T1'] }),
        stand({ id: 'S2', typologiesProposees: [] }),
      ]);

      page.filtre.set('ambiance');

      expect(page.standsFiltres().map((row) => row.id)).toEqual(['S1']);
    });

    it('names the emplacement of a stand', () => {
      const page = createPage();

      expect(
        page.emplacementLabel(
          stand({ id: 'tir', emplacement: emplacement('prairie', 'Grande prairie') }),
        ),
      ).toBe('Grande prairie');
    });

    it('carries no suffix on a plain stand', () => {
      const page = createPage();

      expect(page.effectifSuffix(stand({ id: 'tir' }))).toBe('');
    });

    it('accumulates the adults-only, premium and exhausting markers', () => {
      const page = createPage();

      const suffix = page.effectifSuffix(
        stand({ id: 'tir', reserveMajeurs: true, premium: true, niveauEffort: 'EPUISANT' }),
      );

      expect(suffix).toContain('majeurs');
      expect(suffix).toContain('premium');
      expect(suffix).toContain('épuisant');
    });

    it('marks only what the stand actually is', () => {
      const page = createPage();

      const suffix = page.effectifSuffix(stand({ id: 'tir', premium: true }));

      expect(suffix).toContain('premium');
      expect(suffix).not.toContain('majeurs');
      expect(suffix).not.toContain('épuisant');
    });

    // The days a stand opens and the seats they ask for, from the openings report.
    it('counts the open days and the seats of a stand, a dash before the report is in', () => {
      const page = createPage();

      expect(page.ouvertLabel(stand({ id: 'tir' }))).toBe('—');
    });
  });

  describe('compacting the opening hours', () => {
    it('runs a dry run first and writes nothing when there is nothing to compact', async () => {
      const page = createPage();
      standsApi.compactSchedules.mockResolvedValue({
        standsCompactes: 0,
        fenetresAvant: 0,
        fenetresApres: 0,
      });

      await page.compacterHoraires();

      expect(standsApi.compactSchedules).toHaveBeenCalledExactlyOnceWith(false);
      expect(confirm.ask).not.toHaveBeenCalled();
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'info' }),
      );
    });

    it('shows the trade before writing, and writes nothing when it is refused', async () => {
      const page = createPage();
      standsApi.compactSchedules.mockResolvedValue({
        standsCompactes: 3,
        fenetresAvant: 24,
        fenetresApres: 6,
      });
      confirm.ask.mockResolvedValue(false);

      await page.compacterHoraires();

      expect(confirm.ask).toHaveBeenCalledWith(
        expect.objectContaining({ message: expect.stringContaining('24') }),
      );
      expect(standsApi.compactSchedules).toHaveBeenCalledOnce();
      expect(crud.reload).toHaveBeenCalledOnce();
    });

    it('applies the compaction once confirmed, then reloads and reports', async () => {
      const page = createPage();
      standsApi.compactSchedules.mockResolvedValue({
        standsCompactes: 3,
        fenetresAvant: 24,
        fenetresApres: 6,
      });
      crud.reload.mockClear();

      await page.compacterHoraires();

      expect(standsApi.compactSchedules).toHaveBeenLastCalledWith(true);
      expect(crud.reload).toHaveBeenCalledOnce();
      expect(notifications.notify).toHaveBeenCalledWith(
        expect.objectContaining({ variant: 'success' }),
      );
    });

    it('lowers the in-flight flag whether the round-trip succeeds or fails', async () => {
      const page = createPage();
      standsApi.compactSchedules.mockRejectedValue(new Error('Compactage refusé.'));

      await page.compacterHoraires();

      expect(page.compactageEnCours()).toBe(false);
      expect(crud.reportError).toHaveBeenCalledOnce();
      expect(notifications.notify).not.toHaveBeenCalled();
    });
  });

  it('exposes the editing lock as the job service sees it, not as its own copy', () => {
    const page = createPage();

    expect(page.editingLocked()).toBe(false);
  });

  it('keeps a checkbox column and an actions column around the data ones', () => {
    const page = createPage();

    expect(page.columns()[0]).toBe('select');
    expect(page.columns().at(-1)).toBe('actions');
    // No plan held yet: nothing to cover.
    expect(page.columns()).not.toContain('couverture');
  });
});

describe('StandsPage table', () => {
  let referenceData: ReferenceDataStore;
  let fixture: ComponentFixture<StandsPage>;
  let dialog: { open: ReturnType<typeof vi.fn> };
  const causeParStandId = signal(new Map<string, CauseInfaisabilite>());
  const editingLocked = signal(false);
  /** What `GET /api/editions/courant/gel` answers; nothing frozen unless a test says so. */
  let gel: EtatGel[] = [];
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0),
    reportError: vi.fn(),
    warningsOf: vi.fn(() => []),
  };

  async function rendre(stands: Stand[]): Promise<void> {
    seedStore(referenceData, 'stands', stands);
    fixture = TestBed.createComponent(StandsPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function lignes(): string[][] {
    return Array.from(racine().querySelectorAll('tbody tr')).map((row) =>
      Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent!.trim()),
    );
  }

  function action(indexLigne: number, nom: string): Promise<HTMLButtonElement> {
    return rowMenuItem(fixture, racine().querySelectorAll('tbody tr')[indexLigne], nom);
  }

  /** An entry of the header's « Plus » menu, rendered in the overlay once opened. */
  async function moreMenuItem(libelle: string): Promise<HTMLButtonElement> {
    racine().querySelector<HTMLButtonElement>('button.stands-plus')!.click();
    await fixture.whenStable();
    const panel = Array.from(document.querySelectorAll('.mat-mdc-menu-panel')).at(-1);
    const item = Array.from(
      panel?.querySelectorAll<HTMLButtonElement>('.mat-mdc-menu-item') ?? [],
    ).find((each) => each.textContent?.includes(libelle));
    expect(item, `entrée « ${libelle} » du menu Plus`).toBeDefined();
    return item!;
  }

  function boutonCarte(libelle: string): HTMLButtonElement {
    const bouton = Array.from(racine().querySelectorAll('mat-card-actions button')).find((each) =>
      each.textContent!.includes(libelle),
    );
    expect(bouton, `bouton « ${libelle} » absent`).toBeDefined();
    return bouton as HTMLButtonElement;
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    editingLocked.set(false);
    gel = [];
    causeParStandId.set(new Map());
    crud.reportError.mockClear();
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        {
          provide: ApiService,
          useValue: {
            get: vi.fn(async (url: string) => (url === '/api/editions/courant/gel' ? gel : [])),
          },
        },
        {
          provide: StandsApi,
          useValue: {
            compactSchedules: vi.fn(async () => ({
              standsCompactes: 0,
              fenetresAvant: 0,
              fenetresApres: 0,
            })),
            openings: vi.fn(async () => ({
              jours: [],
              stands: [],
              standsJamaisOuverts: 0,
              postesTotal: 0,
              anomalies: [],
            })),
          },
        },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked } },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: { ask: vi.fn(async () => false) } },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        {
          provide: ProblemesStore,
          useValue: { reloadFeasibility: vi.fn(async () => undefined), causeParStandId },
        },
      ],
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  it('renders the staffing range, its qualifiers and the typologies of each stand', async () => {
    await rendre([
      stand({
        id: 's1',
        nom: 'Loup-Garou',
        effectifMin: 2,
        effectifMax: 4,
        premium: true,
        reserveMajeurs: true,
        typologiesProposees: ['ambiance', 'expert'],
      }),
      stand({ id: 's2', nom: 'Dixit' }),
    ]);

    expect(racine().querySelector('h1')!.textContent!.trim()).toBe('Stands');
    expect(racine().querySelector('mat-card h2')!.textContent!).toContain('Stands (2)');
    expect(lignes()[0][4]).toBe('2–4 · majeurs · premium');
    expect(lignes()[0][5]).toBe('ambiance, expert');
    expect(lignes()[1][5]).toBe('—');
  });

  it('links the name to the fiche and says how much of the event the stand opens', async () => {
    await rendre([stand({ id: 's1', nom: 'Loup-Garou' })]);

    const lien = racine().querySelector<HTMLAnchorElement>('tbody tr a.referentiel-nom')!;
    expect(lien.getAttribute('href')).toBe('/stands/s1');
    expect(lien.textContent!.trim()).toBe('Loup-Garou');
    // No openings report row: the column says nothing rather than a false zero.
    expect(lignes()[0][7]).toBe('—');
  });

  it('flags a stand named by a feasibility cause, for a screen reader too', async () => {
    causeParStandId.set(
      new Map([
        [
          's1',
          {
            type: 'STAND_SANS_COMPETENCE',
            severite: 'CRITIQUE',
            message: 'Aucun animateur compétent pour ce stand.',
            creneauId: null,
            date: null,
            heureDebut: null,
            heureFin: null,
            standIds: ['s1'],
            demande: 1,
            capacite: 0,
            manque: 1,
          } as unknown as CauseInfaisabilite,
        ],
      ]),
    );
    await rendre([stand({ id: 's1', nom: 'Loup-Garou' })]);

    const icone = racine().querySelector('.row-problem-icon')!;
    expect(icone.getAttribute('aria-label')).toBe('Aucun animateur compétent pour ce stand.');
  });

  it('distinguishes an empty referential from a filter that matched nothing', async () => {
    await rendre([]);
    // Its actions are the empty state's alone: the header leaves them out.
    expectOnlyInEmptyState(racine(), ['Ajouter', 'Importer']);
    // The step before the stands is named, and still to do.
    const vide = racine().querySelector('.empty-state')!;
    expect(vide.textContent).toContain('Un stand propose des typologies');
    expect(vide.querySelector('a[href="/typologies"]')!.textContent).toContain(
      'Saisir les typologies',
    );

    await rendre([stand({ id: 's1', nom: 'Loup-Garou' })]);
    const input = racine().querySelector('app-table-filter input') as HTMLInputElement;
    input.value = 'zzz';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(racine().querySelector('.empty-hint')!.textContent!.trim()).toBe(
      'Aucune ligne ne correspond au filtre.',
    );
  });

  it('greys out the writing actions while a solve is running, the horaires compaction included', async () => {
    await rendre([stand({ id: 's1', nom: 'Loup-Garou' })]);
    editingLocked.set(true);
    await fixture.whenStable();

    expect((await action(0, 'Modifier')).disabled).toBe(true);
    expect((await action(0, 'Supprimer')).disabled).toBe(true);
    // The compaction rewrites every stand's horaires: it is a write like any other.
    expect((await moreMenuItem('Compacter les horaires')).disabled).toBe(true);
    expect(boutonCarte('Ajouter').disabled).toBe(true);
  });

  it('greys out what a stands freeze refuses as a whole, with its padlock, but not the edit', async () => {
    gel = [{ famille: 'STANDS', libelle: 'Stands', fige: true, figeLe: '2026-07-01T08:00:00Z' }];
    await rendre([stand({ id: 's1', nom: 'Loup-Garou' })]);
    const page = fixture.componentInstance as unknown as PageInternals;
    page.selection.toggle('s1');
    await fixture.whenStable();

    expect(racine().querySelector('app-gel-notice .gel-notice')).not.toBeNull();
    expect(boutonCarte('Ajouter').disabled).toBe(true);
    expect(boutonCarte('Édition groupée').disabled).toBe(true);
    expect((await moreMenuItem('Compacter les horaires')).disabled).toBe(true);
    expect((await action(0, 'Supprimer')).disabled).toBe(true);
    // A rename or a new location stays open: the form shows the frozen fields read-only.
    expect((await action(0, 'Modifier')).disabled).toBe(false);
    const bulk = Array.from(racine().querySelectorAll('app-bulk-actions-bar button')).filter(
      (bouton) => (bouton as HTMLButtonElement).disabled,
    );
    expect(bulk.length).toBe(2);
  });

  it('leaves every action open while no freeze holds', async () => {
    await rendre([stand({ id: 's1', nom: 'Loup-Garou' })]);

    expect(racine().querySelector('app-gel-notice .gel-notice')).toBeNull();
    expect(boutonCarte('Ajouter').disabled).toBe(false);
    expect((await action(0, 'Supprimer')).disabled).toBe(false);
  });

  it('narrows to the stands of ?typologie=, named in a chip that lets them all back', async () => {
    await TestBed.inject(Router).navigateByUrl('/?typologie=ambiance');
    seedStore(referenceData, 'typologies', [{ id: 'ambiance', label: 'Ambiance' }]);
    await rendre([
      stand({ id: 's1', nom: 'Loup-Garou', typologiesProposees: ['ambiance'] }),
      stand({ id: 's2', nom: 'Dixit', typologiesProposees: ['expert'] }),
    ]);

    expect(lignes().map((row) => row[1])).toEqual(['s1']);
    const chip = racine().querySelector('app-filter-chips mat-chip')!;
    expect(chip.textContent).toContain('Typologie : Ambiance');
    (chip.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(lignes().map((row) => row[1])).toEqual(['s1', 's2']);
  });

  it('sorts on every column, the ids in their natural order', async () => {
    await TestBed.inject(Router).navigateByUrl('/?sort=nom&dir=asc');
    await rendre([stand({ id: 'S10', nom: 'Belote' }), stand({ id: 'S2', nom: 'Awalé' })]);

    expect(lignes().map((row) => row[1])).toEqual(['S2', 'S10']);
    expect(racine().querySelectorAll('th[mat-sort-header]')).toHaveLength(7);
  });

  it('keeps in its header Ajouter, Importer and Édition groupée, the rest under « Plus »', async () => {
    await rendre([stand({ id: 's1', nom: 'Loup-Garou' }), stand({ id: 's2', nom: 'Dixit' })]);

    const header = Array.from(
      racine().querySelectorAll('mat-card-actions > button, mat-card-actions > a'),
    )
      .map((each) => each.textContent!.trim())
      .filter((texte) => texte !== '');
    expect(header.some((texte) => texte.endsWith('Compacter les horaires'))).toBe(false);
    expect(header.some((texte) => texte.endsWith('Saisir en grille'))).toBe(false);
    expect((await moreMenuItem('Saisir en grille')).getAttribute('href')).toBe(
      '/ouvertures?vue=saisie',
    );

    // Nothing ticked: the bulk edit takes every stand the table shows.
    boutonCarte('Édition groupée').click();
    expect(dialog.open.mock.calls.at(-1)![1].data.stands.map((each: Stand) => each.id)).toEqual([
      's1',
      's2',
    ]);
  });

  it('proposes an example when the edition has no stand yet', async () => {
    await rendre([]);

    const exemple = Array.from(racine().querySelectorAll<HTMLAnchorElement>('a')).find((lien) =>
      lien.textContent!.includes('Charger un exemple'),
    );
    expect(exemple?.getAttribute('href')).toBe('/fichiers?onglet=importer&cible=exemples');
  });
});

// Filtering, multi-selection and cell labels of the emplacements table,
// including the nearest-neighbour column that mirrors the solver's 300 m
// threshold. The first half creates the component without rendering it; the
// second one renders the table, because a column computed correctly and a
// column *shown* are two different claims, and only the second one is what the
// user acts on.
//
// Written as the safety net the `<app-reference-table>` extraction needs.
// `distance.spec.ts` owns the great-circle maths; what is pinned here is the
// column built on top of it.

import { Router, provideRouter } from '@angular/router';
import { rowMenuItem } from '../../core/testing/row-menu';
import { provideZonelessChangeDetection, signal, Signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { Emplacement, EtatGel } from '../../core/models';
import { DetailDialog } from '../../shared/detail-dialog';
import { EmplacementFormDialog } from './emplacement-form-dialog';
import { EmplacementsPage } from './emplacements-page';
import { seedStore } from '../../core/testing/seed-store';
import { expectOnlyInEmptyState } from '../../core/testing/empty-state';

// A degree of latitude is ~111 km, so 0.001° ≈ 111 m: near enough to place a
// neighbour deliberately on either side of the 300 m threshold.
function emplacement(id: string, overrides: Partial<Emplacement> = {}): Emplacement {
  return { id, nom: id, latitude: null, longitude: null, ...overrides };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  columns: string[];
  filtre: { set: (value: string) => void };
  emplacementsFiltres: Signal<Emplacement[]>;
  selection: TableSelection<string>;
  editingLocked: Signal<boolean>;
  coordonneesLabel: (emplacement: Emplacement) => string;
  voisinLePlusProche: (emplacement: Emplacement) => string;
  /** Private to the component; reachable here because `private` is compile-time only. */
  voisins: Signal<Map<string, { libelle: string; metres: number | null }>>;
  remove: (emplacement: Emplacement) => Promise<void>;
  removeSelection: () => Promise<void>;
  editSelection: () => void;
};

describe('EmplacementsPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0),
    warningsOf: vi.fn(() => []),
  };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => ({ subscribe: vi.fn() }) })) };

  beforeEach(() => {
    crud.reload.mockClear();
    crud.remove.mockClear();
    crud.removeMany.mockClear();
    dialog.open.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: { get: vi.fn() } },
        { provide: ReferenceCrudService, useValue: crud },
        {
          provide: SolverJobService,
          useValue: { solverBusy: () => false, editingLocked: () => false },
        },
        { provide: MatDialog, useValue: dialog },
      ],
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(emplacements: Emplacement[] = []): PageInternals {
    seedStore(referenceData, 'emplacements', emplacements);
    return TestBed.createComponent(EmplacementsPage).componentInstance as unknown as PageInternals;
  }

  it('loads the referential on entry rather than showing whatever the previous page left', () => {
    createPage();

    expect(crud.reload).toHaveBeenCalledOnce();
  });

  describe('quick filter', () => {
    it('shows every emplacement while the filter is empty', () => {
      const page = createPage([emplacement('prairie'), emplacement('halle')]);

      // The ids in their natural order, by default.
      expect(page.emplacementsFiltres().map((row) => row.id)).toEqual(['halle', 'prairie']);
    });

    it('matches on the id and on the name', () => {
      const page = createPage([
        emplacement('prairie', { nom: 'Grande prairie' }),
        emplacement('halle', { nom: 'Halle couverte' }),
      ]);

      page.filtre.set('couverte');
      expect(page.emplacementsFiltres().map((row) => row.id)).toEqual(['halle']);

      page.filtre.set('prairie');
      expect(page.emplacementsFiltres().map((row) => row.id)).toEqual(['prairie']);
    });

    it('matches on the coordinates, which is how a misplaced point is found', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.12345, longitude: 1.5 }),
        emplacement('halle', { latitude: 48.9, longitude: 1.5 }),
      ]);

      page.filtre.set('47.123');

      expect(page.emplacementsFiltres().map((row) => row.id)).toEqual(['prairie']);
    });

    it('shows nothing rather than everything when nothing matches', () => {
      const page = createPage([emplacement('prairie')]);

      page.filtre.set('introuvable');

      expect(page.emplacementsFiltres()).toEqual([]);
    });
  });

  describe('multi-selection', () => {
    it('deletes exactly the ticked rows', async () => {
      const page = createPage([
        emplacement('prairie'),
        emplacement('halle'),
        emplacement('chapiteau'),
      ]);

      page.selection.toggle('prairie');
      page.selection.toggle('chapiteau');
      await page.removeSelection();

      expect(crud.removeMany).toHaveBeenCalledWith(
        'emplacements',
        ['chapiteau', 'prairie'],
        expect.anything(),
      );
    });

    it('forgets an emplacement deleted in the meantime', () => {
      const page = createPage([emplacement('prairie'), emplacement('halle')]);
      page.selection.toggleAll();

      seedStore(referenceData, 'emplacements', [emplacement('halle')]);

      expect(page.selection.selectedIds()).toEqual(['halle']);
    });

    it('narrows select-all to the filtered rows', () => {
      const page = createPage([emplacement('prairie'), emplacement('halle')]);

      page.filtre.set('prairie');
      page.selection.toggleAll();

      expect(page.selection.selectedIds()).toEqual(['prairie']);
    });

    it('hands the bulk-edit dialog exactly the selected emplacements', () => {
      const page = createPage([emplacement('prairie'), emplacement('halle')]);

      page.selection.toggle('halle');
      page.editSelection();

      expect(dialog.open).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({
          data: { emplacements: [expect.objectContaining({ id: 'halle' })] },
        }),
      );
    });

    it('deletes a single row on its own, without touching the selection', async () => {
      const page = createPage([emplacement('prairie')]);

      await page.remove(emplacement('prairie', { nom: 'Prairie du bas' }));

      expect(crud.remove).toHaveBeenCalledWith('emplacements', 'prairie', expect.anything(), {
        name: { text: 'Prairie du bas' },
      });
      expect(crud.removeMany).not.toHaveBeenCalled();
    });
  });

  describe('coordinates cell', () => {
    it('renders a dash for a place that has never been located', () => {
      const page = createPage();

      expect(page.coordonneesLabel(emplacement('prairie'))).toBe('—');
    });

    it('renders a dash when only one of the two coordinates is set', () => {
      const page = createPage();

      expect(page.coordonneesLabel(emplacement('prairie', { latitude: 47.1 }))).toBe('—');
      expect(page.coordonneesLabel(emplacement('prairie', { longitude: 1.5 }))).toBe('—');
    });

    it('renders both coordinates to five decimals, which is metre-level precision', () => {
      const page = createPage();

      expect(
        page.coordonneesLabel(emplacement('prairie', { latitude: 47.1, longitude: 1.5 })),
      ).toBe('47.10000, 1.50000');
    });

    // Zero is a valid coordinate; `||` would have turned the equator into a dash.
    it('renders a coordinate of zero rather than treating it as missing', () => {
      const page = createPage();

      expect(page.coordonneesLabel(emplacement('prairie', { latitude: 0, longitude: 0 }))).toBe(
        '0.00000, 0.00000',
      );
    });
  });

  describe('nearest-neighbour cell', () => {
    // Read from a `matCellDef`, so once per row per change-detection pass: the
    // scan over every other place has to happen once per referential change.
    it('measures every place once per referential change, not once per row render', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        emplacement('halle', { latitude: 47.102, longitude: 1.5 }),
      ]);

      const voisins = page.voisins();
      page.voisinLePlusProche(emplacement('prairie', { latitude: 47.1, longitude: 1.5 }));
      page.voisinLePlusProche(emplacement('halle', { latitude: 47.102, longitude: 1.5 }));

      expect(page.voisins()).toBe(voisins);
      expect(voisins.get('prairie')?.libelle).toContain('halle');
      expect(voisins.get('halle')?.libelle).toContain('prairie');
    });

    it('says nothing when there is no other located place to measure against', () => {
      const page = createPage([emplacement('prairie', { latitude: 47.1, longitude: 1.5 })]);

      expect(
        page.voisinLePlusProche(emplacement('prairie', { latitude: 47.1, longitude: 1.5 })),
      ).toBe('');
    });

    it('says nothing when the place itself has no coordinates', () => {
      const page = createPage([
        emplacement('prairie'),
        emplacement('halle', { latitude: 47.1, longitude: 1.5 }),
      ]);

      expect(page.voisinLePlusProche(emplacement('prairie'))).toBe('');
    });

    it('ignores the other places that have no coordinates', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        emplacement('halle'),
      ]);

      expect(
        page.voisinLePlusProche(emplacement('prairie', { latitude: 47.1, longitude: 1.5 })),
      ).toBe('');
    });

    it('names the closest neighbour, not merely the first one found', () => {
      const loin = emplacement('loin', { latitude: 47.11, longitude: 1.5 });
      const proche = emplacement('proche', { latitude: 47.101, longitude: 1.5 });
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        loin,
        proche,
      ]);

      const label = page.voisinLePlusProche(
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
      );

      expect(label).toContain('proche');
      expect(label).not.toContain('loin');
    });

    it('never measures a place against itself', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        emplacement('halle', { latitude: 47.102, longitude: 1.5 }),
      ]);

      const label = page.voisinLePlusProche(
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
      );

      expect(label).toContain('halle');
      expect(label).not.toContain('0 m');
    });

    // Mirrors `QualiteConstraints.DISTANCE_ELOIGNEE_METRES` on the server: past
    // that bound the solver penalises a change of emplacement, and the table
    // must say so where the places are edited.
    it('flags a neighbour beyond the 300 m threshold the solver penalises', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        emplacement('halle', { latitude: 47.105, longitude: 1.5 }),
      ]);

      const label = page.voisinLePlusProche(
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
      );

      expect(label).toContain('seuil');
    });

    it('leaves a neighbour within the threshold unflagged', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        emplacement('halle', { latitude: 47.102, longitude: 1.5 }),
      ]);

      const label = page.voisinLePlusProche(
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
      );

      expect(label).not.toContain('seuil');
      expect(label).toContain('m');
    });

    it('falls back to the neighbour id when it has no name', () => {
      const page = createPage([
        emplacement('prairie', { latitude: 47.1, longitude: 1.5 }),
        emplacement('halle', { nom: '', latitude: 47.102, longitude: 1.5 }),
      ]);

      expect(
        page.voisinLePlusProche(emplacement('prairie', { latitude: 47.1, longitude: 1.5 })),
      ).toContain('halle');
    });
  });

  it('exposes the editing lock as the job service sees it, not as its own copy', () => {
    const page = createPage();

    expect(page.editingLocked()).toBe(false);
  });

  it('keeps a checkbox column and an actions column around the data ones', () => {
    const page = createPage();

    expect(page.columns[0]).toBe('select');
    expect(page.columns.at(-1)).toBe('actions');
  });

  // A marker dropped and refused: the map is asked to lay it back where the place still is.
  it('asks the map to lay a marker back when its new position could not be saved', async () => {
    const page = createPage([
      emplacement('hall', { nom: 'Hall A', latitude: 47.2, longitude: -1.5 }),
    ]);
    const internals = page as unknown as {
      move: (move: { id: string; latitude: number; longitude: number }) => Promise<void>;
      mapRevision: Signal<number>;
    };
    const save = vi.fn(async () => false);
    (crud as unknown as { save: typeof save }).save = save;

    await internals.move({ id: 'hall', latitude: 48, longitude: 2 });
    expect(save).toHaveBeenCalled();
    expect(internals.mapRevision()).toBe(1);

    save.mockResolvedValue(true);
    await internals.move({ id: 'hall', latitude: 48, longitude: 2 });
    expect(internals.mapRevision()).toBe(1);
  });
});

describe('EmplacementsPage table', () => {
  let referenceData: ReferenceDataStore;
  let fixture: ComponentFixture<EmplacementsPage>;
  let dialog: { open: ReturnType<typeof vi.fn> };
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0),
    warningsOf: vi.fn(() => []),
  };
  const editingLocked = signal(false);
  /** What `GET /api/editions/courant/gel` answers; nothing frozen unless a test says so. */
  let gel: EtatGel[] = [];

  async function rendre(emplacements: Emplacement[]): Promise<void> {
    seedStore(referenceData, 'emplacements', emplacements);
    fixture = TestBed.createComponent(EmplacementsPage);
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

  beforeEach(() => {
    TestBed.resetTestingModule();
    editingLocked.set(false);
    gel = [];
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: ApiService,
          useValue: {
            get: vi.fn(async (url: string) => (url === '/api/editions/courant/gel' ? gel : [])),
          },
        },
        provideRouter([]),
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked } },
        { provide: MatDialog, useValue: dialog },
      ],
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  it('renders the coordinates and the nearest neighbour of each row', async () => {
    await rendre([
      emplacement('hall', { nom: 'Hall A', latitude: 47.2, longitude: -1.55 }),
      emplacement('salle', { nom: 'Salle B', latitude: 47.201, longitude: -1.55 }),
    ]);

    expect(racine().querySelector('h2')!.textContent!).toContain('Lieux (2)');
    expect(lignes()[0][1]).toBe('hall');
    expect(lignes()[0][5]).toContain('47.2');
    // 0.001° of latitude is ~111 m: under the solver's 300 m threshold.
    expect(lignes()[0][6]).toBe('111 m de Salle B');
  });

  it('warns in the neighbour cell when the closest place is past the threshold', async () => {
    await rendre([
      emplacement('hall', { nom: 'Hall A', latitude: 47.2, longitude: -1.55 }),
      emplacement('loin', { nom: 'Chapiteau', latitude: 47.21, longitude: -1.55 }),
    ]);

    expect(lignes()[0][6]).toContain("au-delà du seuil d'éloignement");
  });

  it('writes an em dash rather than an empty cell for a place with no coordinates', async () => {
    await rendre([emplacement('hall', { nom: 'Hall A' })]);

    expect(lignes()[0][6]).toBe('—');
  });

  it('distinguishes an empty referential from a filter that matched nothing', async () => {
    await rendre([]);
    // Its actions are the empty state's alone: the header leaves them out.
    expectOnlyInEmptyState(racine(), ['Ajouter', 'Importer']);
    expect(racine().querySelector('.empty-state')!.textContent).toContain(
      'Un emplacement est le lieu où se tient un stand',
    );

    await rendre([emplacement('hall', { nom: 'Hall A' })]);
    const input = racine().querySelector('app-table-filter input') as HTMLInputElement;
    input.value = 'zzz';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(racine().querySelector('.empty-hint')!.textContent!.trim()).toBe(
      'Aucune ligne ne correspond au filtre.',
    );
  });

  it('greys out the writing actions while a solve is running, but not the consultation', async () => {
    await rendre([emplacement('hall', { nom: 'Hall A' })]);
    editingLocked.set(true);
    await fixture.whenStable();

    expect((await action(0, 'Modifier')).disabled).toBe(true);
    expect((await action(0, 'Supprimer')).disabled).toBe(true);
    expect((await action(0, 'Détail')).disabled).toBe(false);
  });

  it('greys out creating and deleting under an emplacements freeze, but not the edits', async () => {
    gel = [
      {
        famille: 'TYPOLOGIES_EMPLACEMENTS',
        libelle: 'Typologies & emplacements',
        fige: true,
        figeLe: '2026-07-01T08:00:00Z',
      },
    ];
    await rendre([emplacement('hall', { nom: 'Hall A' })]);
    (racine().querySelector('tbody mat-checkbox input') as HTMLInputElement).click();
    await fixture.whenStable();

    expect(racine().querySelector('app-gel-notice .gel-notice')).not.toBeNull();
    expect((await action(0, 'Supprimer')).disabled).toBe(true);
    expect((await action(0, 'Modifier')).disabled).toBe(false);
    const bulk = (libelle: string) =>
      Array.from(racine().querySelectorAll<HTMLButtonElement>('app-bulk-actions-bar button')).find(
        (each) => each.textContent!.includes(libelle),
      )!;
    // A name or coordinates set in bulk stay open; deleting the rows does not.
    expect(bulk('Modifier la sélection').disabled).toBe(false);
    expect(bulk('Supprimer la sélection').disabled).toBe(true);
  });

  it('sorts every column, the neighbour by its distance rather than its wording', async () => {
    await TestBed.inject(Router).navigateByUrl('/?sort=voisin&dir=desc');
    await rendre([
      emplacement('E1', { nom: 'Hall', latitude: 47.2, longitude: -1.55 }),
      emplacement('E2', { nom: 'Salle', latitude: 47.201, longitude: -1.55 }),
      emplacement('E10', { nom: 'Loin', latitude: 47.25, longitude: -1.55 }),
    ]);

    expect(lignes().map((row) => row[1])).toEqual(['E10', 'E1', 'E2']);
    // « Stands rattachés » sorts too, on how many stand there.
    expect(racine().querySelectorAll('th[mat-sort-header]')).toHaveLength(6);
  });

  it('opens the read-only detail, and hands over to the form when the user asks to edit', async () => {
    await rendre([emplacement('hall', { nom: 'Hall A' })]);
    dialog.open.mockReturnValue({ afterClosed: () => of('edit') });

    (await action(0, 'Détail')).click();
    await fixture.whenStable();

    expect(dialog.open.mock.calls[0][0]).toBe(DetailDialog);
    expect(dialog.open.mock.calls[1][0]).toBe(EmplacementFormDialog);
    expect(dialog.open.mock.calls[1][1].data.emplacement.id).toBe('hall');
  });
});

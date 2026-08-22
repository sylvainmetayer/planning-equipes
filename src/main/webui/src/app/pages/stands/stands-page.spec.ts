// Filtering, multi-selection and cell labels of the stands table, plus the
// horaires compaction it alone carries. The component is created but never
// rendered, so this stays a logic test (the project favours those over full
// DOM rendering) — same shape as `animateurs-page.spec.ts`.
//
// Written as the safety net the `<app-reference-table>` extraction needs: what
// is pinned here is the glue the five reference pages repeat, not the CRUD
// service underneath (`reference-crud.service.spec.ts` owns that).

import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { ConfirmService } from '../../shared/confirm-dialog';
import { Emplacement, HoraireStand, Stand } from '../../core/models';
import { StandsPage } from './stands-page';

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
    ...overrides
  };
}

function emplacement(id: string, nom: string): Emplacement {
  return { id, nom, latitude: null, longitude: null };
}

/** One recurring rule holding `fenetres` windows — a rule is one row, not `fenetres` rows. */
function horaire(fenetres: number): HoraireStand {
  return {
    id: null,
    mode: 'OUVERTURE',
    jours: 'TOUS',
    joursSemaine: [],
    dateDebut: null,
    dateFin: null,
    dates: [],
    fenetres: Array.from({ length: fenetres }, () => ({ heureDebut: '10:00', heureFin: '12:00' })),
    motif: null
  };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  columns: string[];
  filtre: { set: (value: string) => void };
  standsFiltres: Signal<Stand[]>;
  selection: TableSelection<string>;
  compactageEnCours: Signal<boolean>;
  editingLocked: Signal<boolean>;
  typologiesLabel: (stand: Stand) => string;
  effectifSuffix: (stand: Stand) => string;
  emplacementLabel: (stand: Stand) => string;
  horairesLabel: (stand: Stand) => string;
  compacterHoraires: () => Promise<void>;
  remove: (stand: Stand) => Promise<void>;
  removeSelection: () => Promise<void>;
  editSelection: () => void;
  openCreate: () => void;
  edit: (stand: Stand) => void;
};

describe('StandsPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0),
    reportError: vi.fn()
  };
  const api = { get: vi.fn(), post: vi.fn() };
  const confirm = { ask: vi.fn() };
  const notifications = { notify: vi.fn() };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => ({ subscribe: vi.fn() }) })) };

  beforeEach(() => {
    for (const stub of [crud.reload, crud.remove, crud.removeMany, crud.reportError]) {
      stub.mockClear();
    }
    api.get.mockReset();
    api.post.mockReset();
    confirm.ask.mockReset();
    notifications.notify.mockReset();
    dialog.open.mockClear();
    api.get.mockResolvedValue({ feasible: true, manqueAnimateurs: 0, causes: [], totalCauses: 0, message: '' });
    confirm.ask.mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: api },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked: () => false } },
        { provide: MatDialog, useValue: dialog },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: notifications },
        { provide: ProblemesStore, useValue: { reloadFeasibility: vi.fn(async () => undefined), causeParStandId: () => new Map() } }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(stands: Stand[] = []): PageInternals {
    referenceData.stands.set(stands);
    return TestBed.createComponent(StandsPage).componentInstance as unknown as PageInternals;
  }

  it('loads the referential on entry rather than showing whatever the previous page left', () => {
    createPage();

    expect(crud.reload).toHaveBeenCalledOnce();
  });

  describe('quick filter', () => {
    it('shows every stand while the filter is empty', () => {
      const page = createPage([stand({ id: 'tir' }), stand({ id: 'quilles' })]);

      expect(page.standsFiltres().map((row) => row.id)).toEqual(['tir', 'quilles']);
    });

    it('matches on the id and on the name', () => {
      const page = createPage([stand({ id: 'tir', nom: 'Tir à la corde' }), stand({ id: 'quilles', nom: 'Molkky' })]);

      page.filtre.set('molkky');
      expect(page.standsFiltres().map((row) => row.id)).toEqual(['quilles']);

      page.filtre.set('tir');
      expect(page.standsFiltres().map((row) => row.id)).toEqual(['tir']);
    });

    it('matches on a proposed typologie', () => {
      const page = createPage([
        stand({ id: 'tir', typologiesProposees: ['AMBIANCE'] }),
        stand({ id: 'quilles', typologiesProposees: ['STRATEGIE'] })
      ]);

      page.filtre.set('strategie');

      expect(page.standsFiltres().map((row) => row.id)).toEqual(['quilles']);
    });

    it('matches on the emplacement name and id', () => {
      const page = createPage([
        stand({ id: 'tir', emplacement: emplacement('prairie', 'Grande prairie') }),
        stand({ id: 'quilles', emplacement: emplacement('halle', 'Halle') })
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
      const page = createPage([stand({ id: 'tir' }), stand({ id: 'quilles' }), stand({ id: 'palet' })]);

      page.selection.toggle('tir');
      page.selection.toggle('palet');
      await page.removeSelection();

      expect(crud.removeMany).toHaveBeenCalledWith('stands', ['tir', 'palet'], expect.anything());
    });

    // The referential is reloaded after every write: a row that vanished must
    // stop weighing on the selection.
    it('forgets a stand deleted in the meantime', () => {
      const page = createPage([stand({ id: 'tir' }), stand({ id: 'quilles' })]);
      page.selection.toggleAll();

      referenceData.stands.set([stand({ id: 'quilles' })]);

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
        expect.objectContaining({ data: { stands: [expect.objectContaining({ id: 'quilles' })] } })
      );
    });

    it('deletes a single row on its own, without touching the selection', async () => {
      const page = createPage([stand({ id: 'tir' })]);

      await page.remove(stand({ id: 'tir' }));

      expect(crud.remove).toHaveBeenCalledWith('stands', 'tir', expect.anything());
      expect(crud.removeMany).not.toHaveBeenCalled();
    });
  });

  describe('cell labels', () => {
    it('renders a dash rather than an empty cell for a stand without typologie or emplacement', () => {
      const page = createPage();

      expect(page.typologiesLabel(stand({ id: 'tir' }))).toBe('—');
      expect(page.emplacementLabel(stand({ id: 'tir' }))).toBe('—');
    });

    it('lists the typologies of a stand, comma separated', () => {
      const page = createPage();

      expect(page.typologiesLabel(stand({ id: 'tir', typologiesProposees: ['AMBIANCE', 'STRATEGIE'] }))).toBe(
        'AMBIANCE, STRATEGIE'
      );
    });

    it('names the emplacement of a stand', () => {
      const page = createPage();

      expect(page.emplacementLabel(stand({ id: 'tir', emplacement: emplacement('prairie', 'Grande prairie') }))).toBe(
        'Grande prairie'
      );
    });

    it('carries no suffix on a plain stand', () => {
      const page = createPage();

      expect(page.effectifSuffix(stand({ id: 'tir' }))).toBe('');
    });

    it('accumulates the adults-only, premium and exhausting markers', () => {
      const page = createPage();

      const suffix = page.effectifSuffix(
        stand({ id: 'tir', reserveMajeurs: true, premium: true, niveauEffort: 'EPUISANT' })
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

    // Summarised rather than counted raw: the old column read "24" for a stand
    // simply open twice a day, which said nothing about its schedule.
    it('summarises the opening hours as rules and exceptions, never as a window count', () => {
      const page = createPage();

      expect(page.horairesLabel(stand({ id: 'tir' }))).toBe('—');

      const label = page.horairesLabel(
        stand({
          id: 'tir',
          // One rule, twelve windows: what the column must say is "1 rule".
          horaires: [horaire(12)],
          ouvertures: [{ id: null, date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00', motif: null }]
        })
      );

      expect(label).toContain('1');
      expect(label).not.toContain('12');
    });
  });

  describe('compacting the opening hours', () => {
    it('runs a dry run first and writes nothing when there is nothing to compact', async () => {
      const page = createPage();
      api.post.mockResolvedValue({ standsCompactes: 0, fenetresAvant: 0, fenetresApres: 0 });

      await page.compacterHoraires();

      expect(api.post).toHaveBeenCalledExactlyOnceWith('/api/stands/compactage-horaires?appliquer=false', {});
      expect(confirm.ask).not.toHaveBeenCalled();
      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'info' }));
    });

    it('shows the trade before writing, and writes nothing when it is refused', async () => {
      const page = createPage();
      api.post.mockResolvedValue({ standsCompactes: 3, fenetresAvant: 24, fenetresApres: 6 });
      confirm.ask.mockResolvedValue(false);

      await page.compacterHoraires();

      expect(confirm.ask).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('24') }));
      expect(api.post).toHaveBeenCalledOnce();
      expect(crud.reload).toHaveBeenCalledOnce();
    });

    it('applies the compaction once confirmed, then reloads and reports', async () => {
      const page = createPage();
      api.post.mockResolvedValue({ standsCompactes: 3, fenetresAvant: 24, fenetresApres: 6 });
      crud.reload.mockClear();

      await page.compacterHoraires();

      expect(api.post).toHaveBeenLastCalledWith('/api/stands/compactage-horaires?appliquer=true', {});
      expect(crud.reload).toHaveBeenCalledOnce();
      expect(notifications.notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
    });

    it('lowers the in-flight flag whether the round-trip succeeds or fails', async () => {
      const page = createPage();
      api.post.mockRejectedValue(new Error('Compactage refusé.'));

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

    expect(page.columns[0]).toBe('select');
    expect(page.columns.at(-1)).toBe('actions');
  });
});

// Two halves: the multi-selection logic (component created, never rendered),
// then the rendered table. The rendering half exists for one claim in
// particular — deleting a typologie silently strips it from every stand and
// animateur that named it, so the confirmation must carry that count. A
// confirmation that says "irréversible" without saying *what* it takes down is
// the same as no confirmation.
// The ninja picker moved to the Paramètres page, and its tests with it.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { TableSelection } from '../../core/table-selection';
import { TypologiesPage } from './typologies-page';
import type { TypologieItem } from '../../core/models';

/** Reaches the protected members the template binds to. */
type PageInternals = {
  selection: TableSelection<string>;
  removeSelection: () => Promise<void>;
};

describe('TypologiesPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(async () => undefined),
    save: vi.fn(async () => true),
    removeMany: vi.fn(async () => 0)
  };

  beforeEach(() => {
    crud.reload.mockClear();
    crud.save.mockClear();
    crud.removeMany.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn() } },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked: () => false } },
        { provide: MatDialog, useValue: { open: vi.fn() } }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(typologies: TypologieItem[]): PageInternals {
    referenceData.typologies.set(typologies);
    return TestBed.createComponent(TypologiesPage).componentInstance as unknown as PageInternals;
  }

  describe('multi-selection', () => {
    it('supprime exactement les lignes cochées', async () => {
      const page = createPage([
        { id: 'STRATEGIE', label: 'Stratégie' },
        { id: 'JOKER', label: 'Joker' },
        { id: 'AMBIANCE', label: 'Ambiance' }
      ]);

      page.selection.toggle('STRATEGIE');
      page.selection.toggle('AMBIANCE');
      await page.removeSelection();

      expect(crud.removeMany).toHaveBeenCalledWith('typologies', ['STRATEGIE', 'AMBIANCE'], expect.anything());
    });

    // Le référentiel est rechargé après chaque écriture : une ligne disparue
    // ne doit plus peser sur la sélection.
    it('oublie une typologie supprimée entre-temps', () => {
      const page = createPage([
        { id: 'STRATEGIE', label: 'Stratégie' },
        { id: 'JOKER', label: 'Joker' }
      ]);
      page.selection.toggleAll();

      referenceData.typologies.set([{ id: 'JOKER', label: 'Joker' }]);

      expect(page.selection.selectedIds()).toEqual(['JOKER']);
    });
  });
});

describe('TypologiesPage table', () => {
  let referenceData: ReferenceDataStore;
  let fixture: ComponentFixture<TypologiesPage>;
  let dialog: { open: ReturnType<typeof vi.fn> };
  const crud = {
    reload: vi.fn(async () => undefined),
    remove: vi.fn(async (..._args: unknown[]) => true),
    removeMany: vi.fn(async () => 0)
  };
  const editingLocked = signal(false);

  async function rendre(typologies: TypologieItem[]): Promise<void> {
    referenceData.typologies.set(typologies);
    fixture = TestBed.createComponent(TypologiesPage);
    await fixture.whenStable();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function lignes(): string[][] {
    return Array.from(racine().querySelectorAll('tbody tr')).map((row) =>
      Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent!.trim())
    );
  }

  function action(indexLigne: number, nom: string): HTMLButtonElement {
    const boutons = Array.from(
      racine().querySelectorAll('tbody tr')[indexLigne].querySelectorAll('.row-actions button')
    );
    const bouton = boutons.find((each) => each.getAttribute('aria-label') === nom);
    expect(bouton, `action « ${nom} » absente`).toBeDefined();
    return bouton as HTMLButtonElement;
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    editingLocked.set(false);
    crud.remove.mockClear();
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn(async () => []) } },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: SolverJobService, useValue: { solverBusy: () => false, editingLocked } },
        { provide: MatDialog, useValue: dialog }
      ]
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  it('renders one row per typologie and counts them in the title', async () => {
    await rendre([
      { id: 'ambiance', label: 'Ambiance' },
      { id: 'expert', label: 'Expert' }
    ]);

    expect(racine().querySelector('h1')!.textContent!).toContain('Typologies (2)');
    expect(lignes().map((row) => [row[1], row[2]])).toEqual([
      ['ambiance', 'Ambiance'],
      ['expert', 'Expert']
    ]);
  });

  it('marks the ninja typologie, and only it', async () => {
    await rendre([
      { id: 'ambiance', label: 'Ambiance' },
      { id: 'ninja', label: 'Ninja', ninja: true }
    ]);

    const cellules = Array.from(racine().querySelectorAll('tbody tr')).map((row) =>
      row.querySelectorAll('td')[3].querySelector('mat-icon')
    );
    expect(cellules[0]).toBeNull();
    // Announced, not just drawn: the icon carries the whole meaning of the cell.
    expect(cellules[1]!.getAttribute('aria-label')).toBe('Typologie ninja');
  });

  it('tells the user how many rows reference the typologie before deleting it', async () => {
    referenceData.stands.set([
      {
        id: 's1',
        nom: 'Loup-Garou',
        typologiesProposees: ['ambiance'],
        effectifMin: 1,
        effectifMax: 2,
        reserveMajeurs: false,
        premium: false,
        niveauEffort: 'NORMAL',
        emplacement: null,
        indisponibilites: [],
        ouvertures: [],
        horaires: []
      }
    ]);
    referenceData.animateurs.set([
      {
        id: 'a1',
        prenom: 'Amélie',
        nom: 'Nothomb',
        dateNaissance: '1990-01-01',
        manager: false,
        competences: { ambiance: 'REFERENT' },
        souhaits: [],
        joursIndisponibles: []
      }
    ]);
    await rendre([{ id: 'ambiance', label: 'Ambiance' }]);

    action(0, 'Supprimer').click();
    await fixture.whenStable();

    expect(crud.remove).toHaveBeenCalledWith('typologies', 'ambiance', 'Typologie', '1 stand(s) et 1 animateur(s) la référencent.');
  });

  it('says plainly when nothing references the typologie', async () => {
    await rendre([{ id: 'ambiance', label: 'Ambiance' }]);

    action(0, 'Supprimer').click();
    await fixture.whenStable();

    expect(crud.remove).toHaveBeenCalledWith(
      'typologies',
      'ambiance',
      'Typologie',
      'Aucun stand ni animateur ne la référence.'
    );
  });

  it('offers no bulk edit: a typologie carries nothing two rows could share', async () => {
    await rendre([{ id: 'ambiance', label: 'Ambiance' }]);

    (racine().querySelector('tbody mat-checkbox input') as HTMLInputElement).click();
    await fixture.whenStable();

    const barre = racine().querySelector('app-bulk-actions-bar')!;
    expect(barre.textContent!).not.toContain('Modifier la sélection');
    expect(barre.textContent!).toContain('Supprimer la sélection');
  });

  it('greys out the writing actions while a solve is running, but not the consultation', async () => {
    await rendre([{ id: 'ambiance', label: 'Ambiance' }]);
    editingLocked.set(true);
    await fixture.whenStable();

    expect(action(0, 'Modifier').disabled).toBe(true);
    expect(action(0, 'Supprimer').disabled).toBe(true);
    expect(action(0, 'Consulter le détail').disabled).toBe(false);
  });

  it('distinguishes an empty referential from a filter that matched nothing', async () => {
    await rendre([]);
    expect(racine().querySelector('.empty-hint')!.textContent!.trim()).toBe('Aucune typologie pour le moment.');

    await rendre([{ id: 'ambiance', label: 'Ambiance' }]);
    const input = racine().querySelector('app-table-filter input') as HTMLInputElement;
    input.value = 'zzz';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect(racine().querySelector('.empty-hint')!.textContent!.trim()).toBe('Aucune ligne ne correspond au filtre.');
  });

  it('points at the Paramètres page for the ninja choice instead of editing it here', async () => {
    await rendre([{ id: 'ambiance', label: 'Ambiance' }]);

    const lien = Array.from(racine().querySelectorAll('a')).find((each) => each.textContent!.includes('ninja'))!;
    expect(lien.getAttribute('href')).toBe('/parametres');
  });
});

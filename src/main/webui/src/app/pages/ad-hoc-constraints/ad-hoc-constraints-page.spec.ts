import { Location } from '@angular/common';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Animateur, ContrainteAdHoc, Stand } from '../../core/models';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { seedStore } from '../../core/testing/seed-store';
import { AdHocConstraintFormDialog } from './ad-hoc-constraint-form-dialog';
import { AdHocConstraintsPage } from './ad-hoc-constraints-page';

function contrainte(id: string): ContrainteAdHoc {
  return {
    id,
    type: 'AFFECTATION_FORCEE',
    animateursConcernes: [{ id: 'A1' }],
    creneau: null,
    stand: { id: 'S1' },
    raison: 'test',
  };
}

function pair(id: string, type: 'AFFINITE' | 'INCOMPATIBILITE', ids: string[]): ContrainteAdHoc {
  return {
    id,
    type,
    animateursConcernes: ids.map((each) => ({ id: each })),
    creneau: null,
    stand: null,
    raison: 'secret',
  };
}

async function mountList(contraintes: ContrainteAdHoc[], url = '/'): Promise<HTMLElement> {
  await TestBed.inject(Router).navigateByUrl(url);
  const referenceData = TestBed.inject(ReferenceDataStore);
  seedStore(referenceData, 'contraintes', contraintes);
  seedStore(
    referenceData,
    'animateurs',
    ['A', 'B', 'C', 'D'].map((id) => ({ id, prenom: id, nom: 'Test' }) as Animateur),
  );
  seedStore(referenceData, 'stands', [{ id: 'S1', nom: 'Bourse aux jeux' } as Stand]);
  const fixture = TestBed.createComponent(AdHocConstraintsPage);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

function groupedArrival(id: string, issueDeCovoiturage: boolean): ContrainteAdHoc {
  return {
    id,
    type: 'ARRIVEE_GROUPEE',
    animateursConcernes: [{ id: 'A1' }, { id: 'A2' }],
    creneau: null,
    stand: null,
    raison: '',
    issueDeCovoiturage,
  };
}

function actionsOf(root: HTMLElement, id: string): HTMLElement {
  return [...root.querySelectorAll<HTMLElement>('tr.mat-mdc-row')].find((ligne) =>
    ligne.textContent?.includes(id),
  )!;
}

describe('AdHocConstraintsPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = {
    reload: vi.fn(() => Promise.resolve(undefined)),
    remove: vi.fn(() => Promise.resolve(true)),
    removeMany: vi.fn(
      (_resource: string, ids: readonly (string | number)[], _label: string): Promise<number> =>
        Promise.resolve(ids.length),
    ),
  };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
  const editingLocked = signal(false);

  beforeEach(() => {
    editingLocked.set(false);
    crud.reload.mockClear();
    crud.removeMany.mockClear();
    dialog.open.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ReferenceCrudService, useValue: crud },
        { provide: MatDialog, useValue: dialog },
        {
          provide: SolverJobService,
          useValue: { solverBusy: () => false, editingLocked },
        },
        {
          provide: ProblemesStore,
          useValue: {
            reloadFeasibility: vi.fn(() => Promise.resolve(undefined)),
            causeParContrainteAdHocId: signal(new Map()),
          },
        },
      ],
    });
    referenceData = TestBed.inject(ReferenceDataStore);
  });

  function createPage(contraintes: ContrainteAdHoc[]): void {
    seedStore(referenceData, 'contraintes', contraintes);
    TestBed.createComponent(AdHocConstraintsPage);
  }

  it('names the people and the stand of an adjustment, an unknown id kept as-is', async () => {
    seedStore(referenceData, 'animateurs', [
      { id: 'A1', prenom: 'Alice', nom: 'Martin' } as Animateur,
    ]);
    seedStore(referenceData, 'stands', [{ id: 'S1', nom: 'Bourse aux jeux' } as Stand]);
    seedStore(referenceData, 'contraintes', [
      contrainte('AH1'),
      { ...contrainte('AH2'), animateursConcernes: [{ id: 'A9' }], stand: { id: 'S9' } },
    ]);
    const fixture = TestBed.createComponent(AdHocConstraintsPage);
    await fixture.whenStable();

    const lignes = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('tr.mat-mdc-row'),
    ).map((ligne) => ligne.textContent!.replace(/\s+/g, ' '));
    expect(lignes[0]).toContain('Alice Martin');
    expect(lignes[0]).toContain('stand Bourse aux jeux');
    expect(lignes[1]).toContain('A9');
    expect(lignes[1]).toContain('stand S9');
  });

  describe('a grouped arrival from a carpool request', () => {
    it('disables Modifier and Supprimer and links to the Covoiturage tab, a hand-made one not', async () => {
      seedStore(referenceData, 'contraintes', [
        groupedArrival('C1', true),
        groupedArrival('C2', false),
      ]);
      const fixture = TestBed.createComponent(AdHocConstraintsPage);
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;

      const issue = actionsOf(root, 'C1');
      const boutons = [...issue.querySelectorAll('button')];
      expect(boutons).toHaveLength(2);
      expect(boutons.every((bouton) => bouton.disabled)).toBe(true);
      expect(boutons[0].getAttribute('aria-label')).toContain('Disponibilités > Covoiturage');
      const lien = issue.querySelector('a') as HTMLAnchorElement;
      expect(lien.getAttribute('href')).toBe('/disponibilites?onglet=covoiturage');

      const main = actionsOf(root, 'C2');
      expect([...main.querySelectorAll('button')].some((bouton) => bouton.disabled)).toBe(false);
      expect(main.querySelector('a')).toBeNull();
    });

    it('opens no form for it from the edit deep link', async () => {
      await TestBed.inject(Router).navigateByUrl('/?edit=C1');
      createPage([groupedArrival('C1', true)]);
      await Promise.resolve();
      await Promise.resolve();

      expect(dialog.open).not.toHaveBeenCalled();
    });
  });

  /** `?ids=a,b`: the adjustments a problem named, side by side, until « Tout afficher ». */
  describe('the narrowing to the adjustments involved', () => {
    it('shows only the adjustments the URL names, and all of them once asked', async () => {
      await TestBed.inject(Router).navigateByUrl('/?ids=AH2,AH3');
      seedStore(referenceData, 'contraintes', [
        contrainte('AH1'),
        contrainte('AH2'),
        contrainte('AH3'),
      ]);
      const fixture = TestBed.createComponent(AdHocConstraintsPage);
      await fixture.whenStable();
      const page = fixture.componentInstance;

      expect(page['rows']().map((row) => row.id)).toEqual(['AH2', 'AH3']);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('AH2, AH3');

      page['showAll']();
      await fixture.whenStable();
      expect(page['rows']().map((row) => row.id)).toEqual(['AH1', 'AH2', 'AH3']);
      expect(page['onlyIds']()).toEqual([]);
    });

    it('shows everything without the parameter', () => {
      createPage([contrainte('AH1'), contrainte('AH2')]);
      const page = TestBed.createComponent(AdHocConstraintsPage).componentInstance;

      expect(page['rows']()).toHaveLength(2);
    });
  });

  /** `?edit=<id>`: « Voir la fiche » on an adjustment saved with a warning lands here with its form open. */
  describe('the edit deep link', () => {
    it('opens the form of the adjustment named in the URL once the référentiel is in', async () => {
      await TestBed.inject(Router).navigateByUrl('/?edit=AH2');
      createPage([contrainte('AH1'), contrainte('AH2')]);
      await Promise.resolve();

      expect(dialog.open).toHaveBeenCalledOnce();
      expect(dialog.open).toHaveBeenCalledWith(
        AdHocConstraintFormDialog,
        expect.objectContaining({ data: { contrainte: expect.objectContaining({ id: 'AH2' }) } }),
      );
    });

    it('opens nothing for an adjustment the référentiel does not hold', async () => {
      await TestBed.inject(Router).navigateByUrl('/?edit=AH9');
      createPage([contrainte('AH1')]);
      await Promise.resolve();

      expect(dialog.open).not.toHaveBeenCalled();
    });
  });

  /** The network of pairs is gone (issue #719): its `?personne=` narrows the list. */
  describe('the person filter', () => {
    it('keeps the rows naming the person the URL names, and writes it back', async () => {
      const root = await mountList(
        [pair('P1', 'AFFINITE', ['A', 'B']), pair('P2', 'INCOMPATIBILITE', ['C', 'D'])],
        '/?personne=C',
      );

      const rows = Array.from(root.querySelectorAll('tr.mat-mdc-row'));
      expect(rows).toHaveLength(1);
      expect(rows[0].textContent).toContain('P2');
      expect(TestBed.inject(Location).path()).toContain('personne=C');
    });

    it('lists everything without the parameter, and draws no network', async () => {
      const root = await mountList(
        [pair('P1', 'AFFINITE', ['A', 'B']), pair('P2', 'INCOMPATIBILITE', ['C', 'D'])],
        '/',
      );

      expect(root.querySelectorAll('tr.mat-mdc-row')).toHaveLength(2);
      // The selection checkboxes draw their checkmark in SVG; nothing else may.
      expect(root.querySelector('svg:not(.mdc-checkbox__checkmark)')).toBeNull();
      expect(TestBed.inject(Location).path()).not.toContain('personne');
    });
  });

  /** Purging the adjustments of a duplicated edition used to take one click per row. */
  describe('the selection and « Supprimer la sélection »', () => {
    function checkboxes(root: HTMLElement): HTMLInputElement[] {
      return [...root.querySelectorAll<HTMLInputElement>('tbody input[type="checkbox"]')];
    }

    function selectAll(root: HTMLElement): void {
      root.querySelector<HTMLInputElement>('thead input[type="checkbox"]')!.click();
    }

    function bulkButton(root: HTMLElement): HTMLButtonElement {
      return [...root.querySelectorAll<HTMLButtonElement>('.bulk-bar button')].find((each) =>
        each.textContent!.includes('Supprimer la sélection'),
      )!;
    }

    it('selects every row the person filter shows, and those alone', async () => {
      seedStore(referenceData, 'contraintes', [
        pair('P1', 'AFFINITE', ['A', 'B']),
        pair('P2', 'INCOMPATIBILITE', ['C', 'D']),
        pair('P3', 'INCOMPATIBILITE', ['C', 'A']),
      ]);
      const fixture = TestBed.createComponent(AdHocConstraintsPage);
      const page = fixture.componentInstance;
      // An id, as the fiche of an animateur links here.
      page['personne'].set('C');
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;

      selectAll(root);
      await fixture.whenStable();

      expect(page['selection'].selectedIds()).toEqual(['P2', 'P3']);
      expect(root.querySelector('.bulk-bar-count')!.textContent).toContain('2 élément(s)');
      // « Tout sélectionner » reached what the filter shows, and says so.
      expect(root.querySelector('.bulk-bar-scope')).not.toBeNull();

      // Dropping the filter leaves P1 unticked: the selection never grows on its own.
      page['personne'].set('');
      await fixture.whenStable();
      expect(page['selection'].selectedIds()).toEqual(['P2', 'P3']);
      expect(page['selection'].partiallySelected()).toBe(true);
    });

    it('ticks a row with Space, and keeps a carpool-backed one out of the selection', async () => {
      seedStore(referenceData, 'contraintes', [contrainte('AH1'), groupedArrival('C1', true)]);
      const fixture = TestBed.createComponent(AdHocConstraintsPage);
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;
      const page = fixture.componentInstance;
      const rows = [...root.querySelectorAll<HTMLElement>('tr.mat-mdc-row')];

      rows[0].dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
      const covoiturage = new KeyboardEvent('keydown', {
        key: ' ',
        bubbles: true,
        cancelable: true,
      });
      rows[1].dispatchEvent(covoiturage);
      await fixture.whenStable();

      expect(page['selection'].selectedIds()).toEqual(['AH1']);
      expect(covoiturage.defaultPrevented).toBe(false);
      expect(checkboxes(root)[1].disabled).toBe(true);

      selectAll(root);
      await fixture.whenStable();
      // The covoiturage tab cancels it; « tout sélectionner » leaves it there.
      expect(page['selection'].allSelected()).toBe(false);
      expect(page['selection'].selectedIds()).toEqual([]);
      selectAll(root);
      await fixture.whenStable();
      expect(page['selection'].selectedIds()).toEqual(['AH1']);
      expect(page['selection'].allSelected()).toBe(true);
    });

    it('deletes the selection in one batch, and says the next solve has something new', async () => {
      seedStore(referenceData, 'contraintes', [
        contrainte('AH1'),
        contrainte('AH2'),
        contrainte('AH3'),
      ]);
      const fixture = TestBed.createComponent(AdHocConstraintsPage);
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;
      const changed: unknown[] = [];
      fixture.componentInstance.changed.subscribe((event) => changed.push(event));

      checkboxes(root)[0].click();
      checkboxes(root)[2].click();
      await fixture.whenStable();
      bulkButton(root).click();
      await fixture.whenStable();

      expect(crud.removeMany).toHaveBeenCalledOnce();
      expect(crud.removeMany.mock.calls[0]).toEqual([
        'contraintes-ad-hoc',
        ['AH1', 'AH3'],
        'ajustements',
      ]);
      expect(changed).toHaveLength(1);
    });

    it('announces a change when part of the batch went through, and none when nothing did', async () => {
      seedStore(referenceData, 'contraintes', [contrainte('AH1'), contrainte('AH2')]);
      const fixture = TestBed.createComponent(AdHocConstraintsPage);
      await fixture.whenStable();
      const page = fixture.componentInstance;
      const changed: unknown[] = [];
      page.changed.subscribe((event) => changed.push(event));
      page['selection'].toggleAll();

      // One refused (a solve holding the edition): the other one is still gone.
      crud.removeMany.mockResolvedValueOnce(1);
      await page['removeSelection']();
      expect(changed).toHaveLength(1);

      // Cancelled, or every row refused: nothing new for the next solve.
      crud.removeMany.mockResolvedValueOnce(0);
      await page['removeSelection']();
      expect(changed).toHaveLength(1);
    });

    it('refuses a second start while a batch runs, and greys the button meanwhile', async () => {
      seedStore(referenceData, 'contraintes', [contrainte('AH1'), contrainte('AH2')]);
      const fixture = TestBed.createComponent(AdHocConstraintsPage);
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;
      const page = fixture.componentInstance;
      let finish!: (removed: number) => void;
      crud.removeMany.mockReturnValueOnce(new Promise<number>((resolve) => (finish = resolve)));
      checkboxes(root)[0].click();
      await fixture.whenStable();

      const first = page['removeSelection']();
      await fixture.whenStable();
      expect(bulkButton(root).disabled).toBe(true);
      // A second click reaching the handler anyway (a double click) starts nothing.
      await page['removeSelection']();
      expect(crud.removeMany).toHaveBeenCalledOnce();

      finish(1);
      await first;
      await fixture.whenStable();
      expect(bulkButton(root).disabled).toBe(false);
    });

    it('greys the header checkbox out when no row on screen may be ticked', async () => {
      seedStore(referenceData, 'contraintes', [groupedArrival('C1', true)]);
      const fixture = TestBed.createComponent(AdHocConstraintsPage);
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;
      const header = (): HTMLInputElement =>
        root.querySelector<HTMLInputElement>('thead input[type="checkbox"]')!;

      expect(header().disabled).toBe(true);
      // The row's own checkbox says why it cannot be ticked.
      expect(checkboxes(root)[0].getAttribute('aria-label')).toContain(
        'Disponibilités > Covoiturage',
      );

      seedStore(referenceData, 'contraintes', [groupedArrival('C1', true), contrainte('AH1')]);
      await fixture.whenStable();
      expect(header().disabled).toBe(false);
    });

    it('greys the bulk delete out while a solve runs, never the deselect', async () => {
      seedStore(referenceData, 'contraintes', [contrainte('AH1')]);
      const fixture = TestBed.createComponent(AdHocConstraintsPage);
      await fixture.whenStable();
      const root = fixture.nativeElement as HTMLElement;
      checkboxes(root)[0].click();
      editingLocked.set(true);
      await fixture.whenStable();

      expect(bulkButton(root).disabled).toBe(true);
      const deselect = [...root.querySelectorAll<HTMLButtonElement>('.bulk-bar button')].find(
        (each) => each.textContent!.includes('Tout désélectionner'),
      )!;
      expect(deselect.disabled).toBe(false);
    });
  });
});

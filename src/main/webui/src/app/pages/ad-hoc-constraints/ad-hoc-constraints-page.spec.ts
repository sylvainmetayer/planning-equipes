import { Location } from '@angular/common';
import { ApplicationRef, provideZonelessChangeDetection, signal } from '@angular/core';
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

async function mountNetwork(
  contraintes: ContrainteAdHoc[],
  url = '/?vue=reseau',
): Promise<HTMLElement> {
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

describe('AdHocConstraintsPage', () => {
  let referenceData: ReferenceDataStore;
  const crud = { reload: vi.fn(async () => undefined), remove: vi.fn(async () => true) };
  const dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
  const editingLocked = signal(false);

  beforeEach(() => {
    editingLocked.set(false);
    crud.reload.mockClear();
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
            reloadFeasibility: vi.fn(async () => undefined),
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
    type Internals = {
      rows: () => ContrainteAdHoc[];
      onlyIds: () => string[];
      showAll: () => void;
    };

    it('shows only the adjustments the URL names, and all of them once asked', async () => {
      await TestBed.inject(Router).navigateByUrl('/?ids=AH2,AH3');
      seedStore(referenceData, 'contraintes', [
        contrainte('AH1'),
        contrainte('AH2'),
        contrainte('AH3'),
      ]);
      const fixture = TestBed.createComponent(AdHocConstraintsPage);
      await fixture.whenStable();
      const page = fixture.componentInstance as unknown as Internals;

      expect(page.rows().map((row) => row.id)).toEqual(['AH2', 'AH3']);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('AH2, AH3');

      page.showAll();
      await fixture.whenStable();
      expect(page.rows().map((row) => row.id)).toEqual(['AH1', 'AH2', 'AH3']);
      expect(page.onlyIds()).toEqual([]);
    });

    it('shows everything without the parameter', async () => {
      createPage([contrainte('AH1'), contrainte('AH2')]);
      const page = TestBed.createComponent(AdHocConstraintsPage)
        .componentInstance as unknown as Internals;

      expect(page.rows()).toHaveLength(2);
    });
  });

  /** `?edit=<id>`: « Voir la fiche » on an adjustment saved with a warning lands here with its form open. */
  describe('the edit deep link', () => {
    it('opens the form of the adjustment named in the URL once the référentiel is in', async () => {
      await TestBed.inject(Router).navigateByUrl('/?edit=AH2');
      createPage([contrainte('AH1'), contrainte('AH2')]);
      await Promise.resolve();

      expect(dialog.open).toHaveBeenCalledOnce();
      const [, config] = dialog.open.mock.calls[0] as unknown as [
        unknown,
        { data: { contrainte: ContrainteAdHoc } },
      ];
      expect(config.data.contrainte.id).toBe('AH2');
    });

    it('opens nothing for an adjustment the référentiel does not hold', async () => {
      await TestBed.inject(Router).navigateByUrl('/?edit=AH9');
      createPage([contrainte('AH1')]);
      await Promise.resolve();

      expect(dialog.open).not.toHaveBeenCalled();
    });
  });

  describe('the network reading', () => {
    it('opens on the network the URL names, and keeps it there', async () => {
      const root = await mountNetwork([
        pair('X1', 'AFFINITE', ['A', 'B']),
        pair('X2', 'AFFINITE', ['B', 'C']),
        pair('X3', 'INCOMPATIBILITE', ['A', 'C']),
      ]);

      expect(root.querySelector('table[mat-table]')).toBeNull();
      const svg = root.querySelector('svg[role="img"]');
      expect(svg?.getAttribute('aria-label')).toBe(
        '1 grappe(s), 3 personne(s), 1 incompatibilité(s) interne(s)',
      );
      expect(root.querySelectorAll('.reseau-arete-cible')).toHaveLength(3);
      // Named in the text alternative, and the reason nowhere but in a tooltip.
      expect(root.querySelector('.reseau-alternative')?.textContent).toContain(
        'Incompatibilité interne : A Test et C Test',
      );
      expect(root.querySelector('.reseau-alternative')?.textContent).not.toContain('secret');
      expect(TestBed.inject(Location).path()).toContain('vue=reseau');
    });

    it('opens the adjustment when an edge is clicked', async () => {
      const root = await mountNetwork([pair('X1', 'INCOMPATIBILITE', ['A', 'B'])]);

      root.querySelector<SVGGElement>('.reseau-lien')?.dispatchEvent(new MouseEvent('click'));

      expect(dialog.open).toHaveBeenCalledOnce();
      const [, config] = dialog.open.mock.calls[0] as unknown as [
        unknown,
        { data: { contrainte: ContrainteAdHoc } },
      ];
      expect(config.data.contrainte.id).toBe('X1');
    });

    it('shows the disabled « Modifier » of a single-adjustment edge, and why, while a solve runs', async () => {
      editingLocked.set(true);
      const root = await mountNetwork([pair('X1', 'INCOMPATIBILITE', ['A', 'B'])]);

      root.querySelector<SVGGElement>('.reseau-lien')?.dispatchEvent(new MouseEvent('click'));
      await TestBed.inject(ApplicationRef).whenStable();

      expect(dialog.open).not.toHaveBeenCalled();
      const liste = root.querySelector('.reseau-selection--arete');
      expect(liste?.getAttribute('aria-live')).toBe('polite');
      expect(liste?.querySelector('.reseau-verrou')?.textContent).toContain(
        'Modification impossible pendant une résolution du solveur.',
      );
      const boutons = [...(liste?.querySelectorAll<HTMLButtonElement>('li button') ?? [])];
      expect(boutons).toHaveLength(1);
      expect(boutons[0].disabled).toBe(true);
    });

    it('lists every adjustment of an edge carrying several, each one reachable', async () => {
      const root = await mountNetwork([
        pair('X1', 'INCOMPATIBILITE', ['A', 'B']),
        { ...pair('X2', 'INCOMPATIBILITE', ['B', 'A']), stand: { id: 'S1' } },
      ]);

      root.querySelector<SVGGElement>('.reseau-lien')?.dispatchEvent(new MouseEvent('click'));
      await TestBed.inject(ApplicationRef).whenStable();

      // Opening the first one alone would leave X2 out of reach: nothing opens yet.
      expect(dialog.open).not.toHaveBeenCalled();
      const liste = root.querySelector('.reseau-selection--arete');
      const boutons = [...(liste?.querySelectorAll<HTMLButtonElement>('li button') ?? [])];
      expect(boutons.map((bouton) => bouton.getAttribute('aria-label'))).toEqual([
        "Modifier l'ajustement Incompatibilité (partout)",
        "Modifier l'ajustement Incompatibilité (stand Bourse aux jeux)",
      ]);

      boutons[1].click();
      expect(dialog.open).toHaveBeenCalledOnce();
      const [, config] = dialog.open.mock.calls[0] as unknown as [
        unknown,
        { data: { contrainte: ContrainteAdHoc } },
      ];
      expect(config.data.contrainte.id).toBe('X2');
    });

    it('gives each adjustment of a selected person its own « Modifier »', async () => {
      const root = await mountNetwork([
        pair('X1', 'AFFINITE', ['A', 'B']),
        pair('X2', 'AFFINITE', ['A', 'B']),
        pair('X3', 'INCOMPATIBILITE', ['A', 'D']),
      ]);

      const personne = [...root.querySelectorAll<HTMLButtonElement>('.reseau-personne')].find(
        (button) => button.textContent?.trim() === 'A Test',
      );
      personne?.click();
      await TestBed.inject(ApplicationRef).whenStable();

      const selection = root.querySelector('.reseau-selection');
      expect(selection?.querySelectorAll('.reseau-paires > li')).toHaveLength(2);
      const boutons = [...(selection?.querySelectorAll<HTMLButtonElement>('button') ?? [])];
      expect(boutons).toHaveLength(3);
      boutons[1].click();
      const [, config] = dialog.open.mock.calls[0] as unknown as [
        unknown,
        { data: { contrainte: ContrainteAdHoc } },
      ];
      expect(config.data.contrainte.id).toBe('X2');
    });

    it('lists the pairs of a selected person, each with a link to the timeline', async () => {
      const root = await mountNetwork([
        pair('X1', 'AFFINITE', ['A', 'B']),
        pair('X2', 'INCOMPATIBILITE', ['A', 'D']),
      ]);

      const personne = [...root.querySelectorAll<HTMLButtonElement>('.reseau-personne')].find(
        (button) => button.textContent?.trim() === 'A Test',
      );
      personne?.click();
      await TestBed.inject(ApplicationRef).whenStable();

      const selection = root.querySelector('.reseau-selection');
      expect(selection?.querySelectorAll('.reseau-paires > li')).toHaveLength(2);
      const liens = [...(selection?.querySelectorAll('a') ?? [])].map((a) =>
        a.getAttribute('href'),
      );
      expect(liens).toContain('/timeline?animateur=B');
      expect(liens).toContain('/timeline?animateur=A');
    });

    it('says so, with the creation button, when no pair is entered', async () => {
      const root = await mountNetwork([contrainte('AH1')]);
      expect(root.querySelector('svg[role="img"]')).toBeNull();
      expect(root.textContent).toContain("Aucune paire d'affinité ni d'incompatibilité");

      root.querySelector<HTMLButtonElement>('.reseau-vide button')?.click();
      expect(dialog.open).toHaveBeenCalledOnce();
    });

    it('opens on the list without the param, and writes nothing for it', async () => {
      const root = await mountNetwork([pair('X1', 'AFFINITE', ['A', 'B'])], '/');
      expect(root.querySelector('svg[role="img"]')).toBeNull();
      expect(TestBed.inject(Location).path()).not.toContain('vue=');
    });

    // The palette's « Ajustements manuels › Réseau », used from this very page:
    // a real navigation to the same route, which reuses the component.
    it('switches to the network on a navigation to itself, and back to the list', async () => {
      const root = await mountNetwork([pair('X1', 'AFFINITE', ['A', 'B'])], '/');
      const router = TestBed.inject(Router);

      await router.navigateByUrl('/?vue=reseau');
      await TestBed.inject(ApplicationRef).whenStable();
      expect(root.querySelector('svg[role="img"]')).not.toBeNull();

      await router.navigateByUrl('/');
      await TestBed.inject(ApplicationRef).whenStable();
      expect(root.querySelector('svg[role="img"]')).toBeNull();
      expect(root.querySelector('table[mat-table]')).not.toBeNull();
    });
  });
});

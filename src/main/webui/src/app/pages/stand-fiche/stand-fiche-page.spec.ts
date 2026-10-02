// The fiche stand over what it reads: the head, the grid, what the plan did of
// its seats, « précédent / suivant » in the table's order, `?modifier=1` — the
// landing of the `?edit=` links — opening the identity form, and an unknown id.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ComponentType } from '@angular/cdk/portal';
import { MatDialog, MatDialogConfig } from '@angular/material/dialog';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { JourneesTypesApi } from '../../core/api/journees-types-api';
import { StandsApi } from '../../core/api/stands-api';
import { ApiService } from '../../core/api.service';
import { Animateur, Creneau, PlanningEvenement, Stand } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { Fake, fakeOf, provideFake } from '../../core/testing/fake';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StandFormData, StandFormDialog } from '../stands/stand-form-dialog';
import { StandFichePage } from './stand-fiche-page';

function stand(id: string, nom: string, partial: Partial<Stand> = {}): Stand {
  return {
    id,
    nom,
    typologiesProposees: ['T1'],
    effectifMin: 2,
    effectifMax: 3,
    reserveMajeurs: true,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: { id: 'L1', nom: 'Place du marché', latitude: null, longitude: null },
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
    ...partial,
  };
}

const CRENEAU: Creneau = {
  id: 1,
  jour: 1,
  date: '2026-07-11',
  heureDebut: '10:00:00',
  heureFin: '12:00:00',
};

const ALICE: Animateur = {
  id: 'a1',
  prenom: 'Alice',
  nom: 'Martin',
  dateNaissance: '1990-01-01',
  manager: false,
  competences: {},
  souhaits: [],
  joursIndisponibles: [],
};

const PLAN: PlanningEvenement = {
  animateurs: [ALICE],
  postes: [
    { id: 'p1', stand: stand('S2', 'Stand 2'), creneau: CRENEAU, animateur: ALICE },
    { id: 'p2', stand: stand('S2', 'Stand 2'), creneau: CRENEAU, animateur: null },
  ],
  score: null,
};

describe('StandFichePage', () => {
  // The one `MatDialog` method the page calls, typed on what it hands the stand form.
  const dialog = {
    open: vi.fn((_component: ComponentType<unknown>, _config: MatDialogConfig<StandFormData>) => ({
      afterClosed: () => of(false),
    })),
  };
  let fixture: ComponentFixture<StandFichePage>;
  let queryParams: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let store: Fake<ReferenceDataStore>;
  let standsApi: Fake<StandsApi>;

  beforeEach(() => {
    vi.clearAllMocks();
  });

  async function render(
    id: string,
    params: Record<string, string> = {},
    stands: Stand[] = [stand('S1', 'Stand 1'), stand('S2', 'Stand 2'), stand('S3', 'Stand 3')],
  ): Promise<void> {
    queryParams = new BehaviorSubject(convertToParamMap(params));
    store = fakeOf<ReferenceDataStore>({
      stands: signal(stands),
      typologies: signal([{ id: 'T1', label: 'Enfance', ninja: false }]),
      emplacements: signal([]),
      creneaux: signal([]),
      reload: () => Promise.resolve(undefined),
    });
    standsApi = fakeOf<StandsApi>({
      openings: () =>
        Promise.resolve({
          jours: [],
          stands: [
            {
              standId: 'S2',
              nom: 'Stand 2',
              effectifMin: 2,
              jours: [],
              minutesOuvertes: 0,
              postes: 56,
              modifieLe: null,
            },
          ],
          standsJamaisOuverts: 0,
          postesTotal: 56,
          anomalies: [
            {
              type: 'FENETRE_SANS_EFFET',
              standId: 'S2',
              standNom: 'Stand 2',
              date: '2026-07-12',
              message: 'Fenêtre 07:00-08:00 hors de tout créneau',
            },
          ],
        }),
    });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ApiService, useValue: { get: vi.fn(() => Promise.resolve([])) } },
        provideFake(StandsApi, standsApi),
        { provide: JourneesTypesApi, useValue: { etat: vi.fn(() => Promise.resolve(null)) } },
        {
          provide: PlanningStateService,
          useValue: { set: vi.fn(), loadForDisplay: vi.fn(() => Promise.resolve(PLAN)) },
        },
        {
          provide: ProblemesStore,
          useValue: {
            reloadFeasibility: vi.fn(() => Promise.resolve(undefined)),
            causeParStandId: () => new Map(),
          },
        },
        provideFake(ReferenceDataStore, store),
        { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
        { provide: ConfirmService, useValue: { ask: vi.fn(() => Promise.resolve(true)) } },
        { provide: MatDialog, useValue: dialog },
        {
          provide: Location,
          // `?modifier=1`, once obeyed, is dropped through a router navigation.
          useValue: {
            path: () => `/stands/${id}`,
            replaceState: vi.fn(),
            go: vi.fn(),
            isCurrentPathEqualTo: () => false,
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of(convertToParamMap({ id })),
            queryParamMap: queryParams,
            snapshot: {
              paramMap: convertToParamMap({ id }),
              queryParamMap: convertToParamMap(params),
            },
          },
        },
      ],
    });
    fixture = TestBed.createComponent(StandFichePage);
    await fixture.whenStable();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(
        root().querySelector('app-stand-grid-editor, .stand-fiche-introuvable'),
      ).not.toBeNull();
    });
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return (root().textContent ?? '').replace(/\s+/g, ' ');
  }

  it('says what the stand is in its head, with its game categories and its location as links', async () => {
    await render('S2');

    expect(root().querySelector('h1')!.textContent!.trim()).toBe('Stand 2');
    expect(text()).toContain('2 à 3 personne(s)');
    expect(text()).toContain('réservé aux majeurs');
    expect(text()).toContain('56 poste(s)');
    const liens = Array.from(root().querySelectorAll<HTMLAnchorElement>('mat-card-subtitle a')).map(
      (lien) => lien.getAttribute('href'),
    );
    expect(liens).toContain('/typologies?q=Enfance');
    expect(liens).toContain('/stands?onglet=lieux&q=Place%20du%20march%C3%A9');
  });

  it('shows its anomalies, and what the plan did of its seats, the empty one in the Siège panel', async () => {
    await render('S2');

    expect(text()).toContain('Fenêtre 07:00-08:00 hors de tout créneau');
    expect(text()).toContain('2 poste(s) · 1 pourvu(s) · 1 vide(s)');
    const vides = Array.from(
      root().querySelectorAll<HTMLAnchorElement>('#stand-section-plan li a'),
    );
    expect(vides.map((lien) => lien.getAttribute('href'))).toEqual([
      '/journee?date=2026-07-11&siege=p2',
    ]);
  });

  it('walks the stands in the order the table was sorted', async () => {
    await render('S2', { sort: 'nom', dir: 'desc' });

    const precedent = root().querySelector<HTMLAnchorElement>('a[rel="prev"]')!;
    const suivant = root().querySelector<HTMLAnchorElement>('a[rel="next"]')!;
    expect(precedent.textContent).toContain('Stand 3');
    expect(precedent.getAttribute('href')).toBe('/stands/S3?sort=nom&dir=desc');
    expect(suivant.textContent).toContain('Stand 1');
  });

  it('opens the identity form, cut down to identity, from `?modifier=1`', async () => {
    await render('S2', { modifier: '1' });

    await vi.waitFor(() => expect(dialog.open).toHaveBeenCalled());
    const [component, config] = dialog.open.mock.calls[0];
    expect(component).toBe(StandFormDialog);
    expect(config.data?.stand?.id).toBe('S2');
    expect(config.data?.identityOnly).toBe(true);
  });

  it('reads the stand again once its grid is saved, its new stamp and headcounts with it', async () => {
    await render('S2');
    store.reload.mockClear();
    const before = standsApi.openings.mock.calls.length;

    fixture.componentInstance['onGridSaved']();
    await fixture.whenStable();

    expect(store.reload).toHaveBeenCalledWith(['stands']);
    expect(standsApi.openings.mock.calls.length).toBeGreaterThan(before);
  });

  it('answers an unknown id with a sentence', async () => {
    await render('S9');

    expect(text()).toContain("Aucun stand ne porte l'identifiant « S9 »");
  });
});

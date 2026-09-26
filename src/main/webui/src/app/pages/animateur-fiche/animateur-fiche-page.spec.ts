// The fiche page over its one call: the seven sections, the first three open;
// the head's gestures; « précédent / suivant » through the list the reader
// came from; the strip's day made unavailable in two clicks, the seat it
// frees offered to a replacement; the dependent sections announcing an empty
// plan, and an unknown id answered with a sentence rather than a blank card.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { PlanningApi } from '../../core/api/planning-api';
import { PostesApi } from '../../core/api/postes-api';
import { ApiError } from '../../core/api.service';
import { ConsignesStore } from '../../core/consignes.store';
import { Animateur, AnimateurProfile } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { AnimateurFichePage } from './animateur-fiche-page';

function profile(partial: Partial<AnimateurProfile> = {}): AnimateurProfile {
  return {
    animateur: {
      id: 'a1',
      prenom: 'Camille',
      nom: 'Durand',
      dateNaissance: '1990-01-01',
      manager: false,
      competences: { JEU: 'REFERENT' },
      souhaits: ['CUBE'],
      joursIndisponibles: ['2026-07-12'],
      email: null,
      accessToken: 'tok',
    },
    joursEvenement: ['2026-07-11', '2026-07-12'],
    regimeDebut: { date: '2026-07-11', age: 36, regime: 'MAJEUR' },
    regimeFin: { date: '2026-07-12', age: 36, regime: 'MAJEUR' },
    planCalcule: true,
    equite: {
      heureDebutSoiree: '20:00:00',
      semaines: [],
      lignes: [
        {
          animateurId: 'a1',
          nom: 'Camille Durand',
          heuresTotal: 6,
          heuresParSemaine: {},
          heuresSoiree: 2,
          heuresWeekEnd: 6,
          heuresJourFerie: 0,
          postes: 2,
          postesPenibles: 0,
          standsDistincts: 1,
          typologiesDistinctes: 1,
          emplacementsDistinctsParJourMax: 1,
          tauxSouhaits: 0.5,
          tauxAppreciation: 1,
          joursTravailles: 1,
          joursRepos: 1,
          plusLongueSerie: 1,
        },
      ],
      syntheses: { heuresTotal: { mediane: 4, min: 2, max: 6, ecartType: 1 } },
      colonnesSolveur: [],
    },
    fragilite: {
      animateurId: 'a1',
      nom: 'Camille Durand',
      ninja: false,
      affectations: 2,
      postesEffondres: 1,
      postesIrremplacables: 1,
      competencesRares: 0,
      severite: 'CRITIQUE',
      postes: [
        {
          standId: 's1',
          standNom: 'Échecs',
          creneauId: 1,
          date: '2026-07-11',
          jour: 1,
          heureDebut: '14:00:00',
          heureFin: '18:00:00',
          effectifMin: 2,
          couverturePause: false,
          siegesRequis: 2,
          siegesPourvus: 2,
          siegesLiberes: 1,
          remplacants: 0,
          irremplacable: true,
        },
      ],
      postesNonDetailles: 0,
    },
    competencesRares: [],
    affectations: [
      {
        posteId: 'p1',
        standId: 's1',
        standNom: 'Échecs',
        creneauId: 1,
        date: '2026-07-11',
        heureDebut: '14:00:00',
        heureFin: '18:00:00',
        emplacementId: 'e1',
        emplacementNom: 'Grande salle',
        passe: true,
        verrouille: false,
      },
    ],
    confirmation: null,
    dernierePublicationLe: null,
    echangesEnCours: [],
    ajustements: [],
    verrous: [],
    declarationEnAttente: null,
    ...partial,
  };
}

function person(id: string, prenom: string, nom: string): Animateur {
  return {
    id,
    prenom,
    nom,
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
  };
}

describe('AnimateurFichePage', () => {
  const api = {
    profile: vi.fn(),
    markDayOff: vi.fn(),
    cancelDayOff: vi.fn(),
    remind: vi.fn(),
    confirmations: vi.fn(async () => []),
    syntheseConfirmations: vi.fn(async () => ({ dernierePublicationLe: null })),
  };
  const planningApi = {
    sendToAnimateur: vi.fn(async () => ({})),
    exportForAnimateur: vi.fn(async () => 'Téléchargement démarré.'),
    equityReport: vi.fn(),
  };
  const verrous = { create: vi.fn(async () => []), remove: vi.fn(async () => undefined) };
  const crud = { save: vi.fn(async () => true) };
  const confirm = { ask: vi.fn(async () => true) };
  const notify = vi.fn();
  const dialog = { open: vi.fn() };
  const planningState = {
    set: vi.fn(),
    loadForDisplay: vi.fn(async () => ({ postes: [], animateurs: [] })),
    require: vi.fn(async () => ({ postes: [] })),
  };
  let fixture: ComponentFixture<AnimateurFichePage>;
  /** The id in the address: « précédent / suivant » moves it without building the page again. */
  let paramMap: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

  function configure(queryParams: Record<string, string> = {}): void {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AnimateursApi, useValue: api },
        { provide: PlanningApi, useValue: planningApi },
        { provide: PostesApi, useValue: { place: vi.fn() } },
        {
          provide: ConstraintsApi,
          useValue: { catalogue: vi.fn(async () => ({ contraintes: [] })) },
        },
        { provide: VerrouillageStore, useValue: verrous },
        { provide: ReferenceCrudService, useValue: crud },
        { provide: ConfirmService, useValue: confirm },
        { provide: NotificationService, useValue: { notify } },
        { provide: MatDialog, useValue: dialog },
        { provide: PlanningStateService, useValue: planningState },
        {
          provide: AnalysesApi,
          useValue: {
            typologies: vi.fn(async () => []),
            breaks: vi.fn(async () => null),
            walks: vi.fn(async () => null),
          },
        },
        {
          provide: ConsignesStore,
          useValue: { reload: vi.fn(async () => undefined), consigneOf: () => null },
        },
        { provide: Location, useValue: { path: () => '/animateurs/a1', replaceState: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap,
            snapshot: {
              paramMap: convertToParamMap({ id: 'a1' }),
              queryParamMap: convertToParamMap(queryParams),
              fragment: null,
            },
          },
        },
        {
          provide: ReferenceDataStore,
          useValue: {
            typologies: signal([
              { id: 'JEU', label: 'Jeux' },
              { id: 'CUBE', label: 'Casse-tête' },
              { id: 'LOG', label: 'Logistique' },
            ]),
            animateurs: signal([
              person('a0', 'Alice', 'Bernard'),
              person('a1', 'Camille', 'Durand'),
              person('a2', 'Zoé', 'Martin'),
            ]),
            creneaux: signal([
              { id: 'c1', date: '2026-07-10', heureDebut: '10:00', heureFin: '12:00' },
            ]),
            reload: vi.fn(async () => undefined),
          },
        },
        { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      ],
    });
  }

  beforeEach(() => {
    vi.clearAllMocks();
    api.confirmations.mockResolvedValue([]);
    paramMap = new BehaviorSubject(convertToParamMap({ id: 'a1' }));
  });

  async function render(queryParams: Record<string, string> = {}): Promise<string> {
    configure(queryParams);
    fixture = TestBed.createComponent(AnimateurFichePage);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text.includes('Identité et contact') || text.includes('Aucun animateur')).toBe(true);
    });
    await fixture.whenStable();
    return text();
  }

  function text(): string {
    return ((fixture.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function button(label: string): HTMLButtonElement {
    const found = Array.from(root().querySelectorAll('button')).find((each) =>
      each.textContent!.includes(label),
    );
    expect(found, `bouton « ${label} » absent`).toBeDefined();
    return found as HTMLButtonElement;
  }

  it('shows the seven sections, the first three open', async () => {
    api.profile.mockResolvedValue(profile());
    const content = await render();

    expect(api.profile).toHaveBeenCalledWith('a1');
    const sections = Array.from(
      root().querySelectorAll<HTMLDetailsElement>('details.fiche-section'),
    );
    expect(sections.map((section) => section.querySelector('h2')!.textContent!.trim())).toEqual([
      'Identité et contact',
      'Disponibilités',
      'Planning',
      'Charge et équité',
      'Compétences et souhaits',
      'Fragilité',
      'Échanges et suivi',
    ]);
    expect(sections.map((section) => section.open)).toEqual([
      true,
      true,
      true,
      false,
      false,
      false,
      false,
    ]);
    expect(content).toContain('Camille Durand');
    expect(content).toContain('souhaitée sans être appréciée');
    expect(root().querySelector('app-animateur-timeline')).not.toBeNull();
  });

  it('opens the section the address names, the old timeline links included', async () => {
    api.profile.mockResolvedValue(profile());
    await render({ section: 'equite' });

    const equite = root().querySelector<HTMLDetailsElement>('#fiche-section-equite')!;
    expect(equite.open).toBe(true);
    expect(text()).toContain('+2');
    expect(root().querySelector('app-equite-radar')).not.toBeNull();
  });

  it('carries the head of actions, the adjustment form pre-filled on the person', async () => {
    api.profile.mockResolvedValue(profile());
    await render();

    for (const label of [
      "Copier le lien d'espace",
      'Envoyer son planning',
      'Relancer',
      'PDF',
      'ICS',
      'Verrouiller tout son planning',
      'Poser un ajustement',
      'Modifier la fiche',
    ]) {
      expect(button(label)).toBeDefined();
    }
    dialog.open.mockReturnValue({ afterClosed: () => of(false) });
    button('Poser un ajustement').click();
    await fixture.whenStable();
    const data = dialog.open.mock.calls[0][1].data;
    expect(data.contrainte.animateursConcernes).toEqual([{ id: 'a1' }]);
  });

  it('sends the planning after asking, and locks the whole planning in one gesture', async () => {
    api.profile.mockResolvedValue(
      profile({ animateur: { ...profile().animateur, email: 'camille@exemple.org' } }),
    );
    await render();

    button('Envoyer son planning').click();
    await vi.waitFor(() => expect(planningApi.sendToAnimateur).toHaveBeenCalledWith('a1'));
    expect(confirm.ask).toHaveBeenCalled();

    button('Verrouiller tout son planning').click();
    await vi.waitFor(() =>
      expect(verrous.create).toHaveBeenCalledWith({ type: 'ANIMATEUR', animateurId: 'a1' }),
    );
  });

  it('walks the list as the reader filtered and sorted it, its view kept in the links', async () => {
    api.profile.mockResolvedValue(profile());
    await render({ sort: 'nom', dir: 'desc' });

    // Descending by name: Zoé Martin, Camille Durand, Alice Bernard.
    const previous = root().querySelector<HTMLAnchorElement>('a[rel="prev"]')!;
    const next = root().querySelector<HTMLAnchorElement>('a[rel="next"]')!;
    expect(previous.textContent).toContain('Zoé Martin');
    expect(previous.getAttribute('href')).toBe('/animateurs/a2?sort=nom&dir=desc');
    expect(next.textContent).toContain('Alice Bernard');
    expect(text()).toContain('2 / 3 de la liste filtrée');
  });

  it('marks a day unavailable in two clicks and offers its freed seat to a replacement', async () => {
    api.profile.mockResolvedValue(
      profile({
        affectations: [{ ...profile().affectations[0], passe: false }],
        animateur: { ...profile().animateur, joursIndisponibles: [] },
      }),
    );
    api.markDayOff.mockImplementation(async () => {
      api.profile.mockResolvedValue(
        profile({
          affectations: [],
          animateur: { ...profile().animateur, joursIndisponibles: ['2026-07-11'] },
        }),
      );
      return {
        date: '2026-07-11',
        unavailable: true,
        startedSeatsKept: 0,
        lockedSeatsKept: [],
        freedSeats: [
          {
            posteId: 'p1',
            standId: 's1',
            standNom: 'Échecs',
            creneauId: 1,
            date: '2026-07-11',
            heureDebut: '14:00:00',
            heureFin: '18:00:00',
          },
        ],
      };
    });
    await render();
    const readsBefore = planningState.loadForDisplay.mock.calls.length;

    const jour = Array.from(root().querySelectorAll<HTMLButtonElement>('button.fiche-jour'))[0];
    jour.click();
    await fixture.whenStable();
    expect(text()).toContain('1 poste(s) : Échecs 14:00–18:00');

    button('Indisponible ce jour').click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(text()).toContain('1 siège(s) libéré(s)');
    });
    expect(api.markDayOff).toHaveBeenCalledWith('a1', '2026-07-11');
    expect(button('Qui peut tenir ce siège ?')).toBeDefined();
    expect(button("Annuler l'absence")).toBeDefined();
    // The button pressed gave way to its opposite: the focus is on the day's heading.
    await vi.waitFor(() => expect(document.activeElement?.id).toBe('fiche-jour-titre'));
    // The open « Planning » section reads the plan again, the freed seat gone from it.
    expect(planningState.loadForDisplay.mock.calls.length).toBeGreaterThan(readsBefore);
  });

  it('writes the day all the same under a lock, and names the seats the lock kept', async () => {
    const locked = { ...profile().affectations[0], passe: false, verrouille: true };
    api.profile.mockResolvedValue(
      profile({
        affectations: [locked],
        animateur: { ...profile().animateur, joursIndisponibles: [] },
      }),
    );
    api.markDayOff.mockImplementation(async () => {
      api.profile.mockResolvedValue(
        profile({
          affectations: [locked],
          animateur: { ...profile().animateur, joursIndisponibles: ['2026-07-11'] },
        }),
      );
      return {
        date: '2026-07-11',
        unavailable: true,
        startedSeatsKept: 0,
        freedSeats: [],
        lockedSeatsKept: [
          {
            posteId: 'p1',
            standId: 's1',
            standNom: 'Échecs',
            creneauId: 1,
            date: '2026-07-11',
            heureDebut: '14:00:00',
            heureFin: '18:00:00',
          },
        ],
      };
    });
    await render();

    root().querySelector<HTMLButtonElement>('button.fiche-jour')!.click();
    await fixture.whenStable();
    expect(text()).toContain('1 poste(s) verrouillé(s), gardé(s) en place : Échecs 14:00–18:00');

    button('Indisponible ce jour').click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(text()).toContain('1 siège(s) verrouillé(s) gardé(s) en place');
    });
    expect(text()).toContain('Aucun siège à libérer.');
    const link = Array.from(root().querySelectorAll<HTMLAnchorElement>('a')).find(
      (each) => each.textContent!.trim() === 'Gérer les verrous',
    );
    expect(link?.getAttribute('href')).toBe('/verrouillages?animateur=a1');
  });

  it('drops the answer of a day marked for the previous person once « Suivant » was pressed', async () => {
    api.profile.mockResolvedValue(
      profile({ animateur: { ...profile().animateur, joursIndisponibles: [] } }),
    );
    let answer: (value: unknown) => void = () => undefined;
    api.markDayOff.mockReturnValue(new Promise((resolve) => (answer = resolve)));
    await render();
    root().querySelector<HTMLButtonElement>('button.fiche-jour')!.click();
    await fixture.whenStable();
    button('Indisponible ce jour').click();

    paramMap.next(convertToParamMap({ id: 'a2' }));
    answer({
      date: '2026-07-11',
      unavailable: true,
      startedSeatsKept: 0,
      freedSeats: [],
      lockedSeatsKept: [],
    });
    await fixture.whenStable();

    const page = fixture.componentInstance as unknown as { dayOutcome: () => unknown };
    expect(page.dayOutcome()).toBeNull();
  });

  it('forgets the radar axes and the compared person on a change of person', async () => {
    api.profile.mockResolvedValue(profile());
    await render({ section: 'equite', axes: 'heuresJourFerie', comparer: 'a2' });
    const page = fixture.componentInstance as unknown as {
      radarAxes: () => string[];
      comparedId: () => string;
    };
    expect(page.radarAxes()).toEqual(['heuresJourFerie']);
    expect(page.comparedId()).toBe('a2');

    paramMap.next(convertToParamMap({ id: 'a0' }));
    await fixture.whenStable();

    expect(page.radarAxes()).toEqual([]);
    expect(page.comparedId()).toBe('');
  });

  it('names the person in the snack bar of a sent planning, and only their id in the journal', async () => {
    api.profile.mockResolvedValue(
      profile({ animateur: { ...profile().animateur, email: 'camille@exemple.org' } }),
    );
    await render();

    button('Envoyer son planning').click();
    await vi.waitFor(() =>
      expect(notify).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Planning envoyé',
          message: 'Camille Durand',
          messageJournal: 'a1',
        }),
      ),
    );
  });

  it('edits the competences in place and saves them through the fiche write', async () => {
    api.profile.mockResolvedValue(profile());
    await render({ section: 'competences' });

    const select = root().querySelector<HTMLSelectElement>('select.fiche-niveau')!;
    select.value = 'AUTONOME';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    button('Enregistrer').click();
    await vi.waitFor(() => expect(crud.save).toHaveBeenCalled());

    const [ressource, payload, id] = crud.save.mock.calls[0] as unknown as [
      string,
      Animateur,
      string,
    ];
    expect(ressource).toBe('animateurs');
    expect(id).toBe('a1');
    expect(payload.competences).toEqual({ JEU: 'REFERENT', CUBE: 'AUTONOME' });
    expect(payload.souhaits).toEqual(['CUBE']);
  });

  it('keeps unsaved competence edits across a reload another section caused', async () => {
    api.profile.mockResolvedValue(profile());
    await render({ section: 'competences' });
    const page = fixture.componentInstance as unknown as {
      competenceLines: () => { typologieId: string; niveau: string | null }[];
      reload: () => void;
    };
    const select = root().querySelector<HTMLSelectElement>('select.fiche-niveau')!;
    select.value = 'AUTONOME';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    // A gesture of another section — a lock, a day off — reads the fiche again.
    api.profile.mockResolvedValue(profile({ verrous: [] }));
    page.reload();
    await vi.waitFor(() => expect(api.profile).toHaveBeenCalledTimes(2));
    await fixture.whenStable();

    const cube = page.competenceLines().find((line) => line.typologieId === 'CUBE');
    expect(cube?.niveau).toBe('AUTONOME');
    expect(button('Enregistrer').disabled).toBe(false);
  });

  it('says so in the dependent sections when no plan was computed', async () => {
    api.profile.mockResolvedValue(
      profile({
        planCalcule: false,
        equite: { ...profile().equite, lignes: [] },
        fragilite: null,
        affectations: [],
      }),
    );
    const content = await render();

    expect(content.match(/Aucun planning calculé/g)?.length).toBe(3);
  });

  it('answers an unknown id with a sentence, not an empty page', async () => {
    api.profile.mockRejectedValue(new ApiError(404, 'notFound', 'Animateur inconnu : a1'));
    const content = await render();

    expect(content).toContain("Aucun animateur ne porte l'identifiant « a1 »");
    expect(content).not.toContain('Identité et contact');
  });
});

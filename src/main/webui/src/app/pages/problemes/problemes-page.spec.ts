import { Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import {
  Animateur,
  ConstraintsView,
  Creneau,
  ConstraintView,
  FeasibilityReport,
  PlanningEvenement,
  RapportPauses,
  Stand,
} from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { SolverJobService } from '../../core/solver-job.service';
import { SeatPlacement } from '../../shared/siege-panel/seat-placement';
import { ProblemesPage } from './problemes-page';

@Component({ template: '' })
class Target {}

const FEASIBILITY: FeasibilityReport = {
  feasible: false,
  manqueAnimateurs: 3,
  totalCauses: 1,
  causesCritiques: 0,
  causesElevees: 1,
  message: 'Il manque du monde.',
  causes: [
    {
      type: 'CRENEAU_SOUS_EFFECTIF',
      severite: 'ELEVE',
      message: 'Le 2026-07-12 10:00-12:00, il manque 3 animateurs pour couvrir le stand S1.',
      creneauId: '42',
      date: '2026-07-12',
      heureDebut: '10:00',
      heureFin: '12:00',
      standIds: ['S1'],
      contrainteIds: [],
      demande: 5,
      capacite: 2,
      manque: 3,
      actions: [
        {
          code: 'VOIR_BANC',
          libelle: 'Qui peut tenir ce siège ?',
          explication: 'Le panneau du siège liste qui est libre sur ce créneau.',
          route: '/journee',
          parametres: { creneau: '42', stand: 'S1' },
        },
        {
          code: 'BAISSER_EFFECTIF',
          libelle: "Baisser l'effectif demandé",
          explication: 'Moins de places ouvertes ce jour-là.',
          route: '/ouvertures',
          parametres: { vue: 'saisie', stand: 'S1' },
        },
      ],
    },
  ],
};

const EQUILIBRE: ConstraintView = {
  name: 'equilibrerCharge',
  libelleCourt: 'Équilibre de la charge',
  niveau: 'MEDIUM',
  categorie: "Qualité d'organisation",
  description: 'La charge de travail doit être répartie équitablement.',
  remediation: 'La charge est inégale : cherchez qui est très au-dessus.',
  actif: true,
  protegee: false,
  legale: false,
  activeByDefault: true,
  dosable: true,
  poids: 5,
  score: '0hard/-30medium/0soft',
  matchCount: 3,
  violations: [],
  postesEvalues: null,
  plancher: null,
  references: [],
  actions: [
    {
      code: 'VOIR_CHARGE',
      libelle: 'Voir la charge par personne',
      explication: 'La charge est inégale : cherchez qui est très au-dessus.',
      route: '/journee',
      parametres: { axe: 'personne' },
    },
    {
      code: 'BAISSER_POIDS',
      libelle: "Baisser l'importance",
      explication: 'En dernier recours.',
      route: '/regles',
      parametres: { onglet: 'qualite', regle: 'equilibrerCharge' },
    },
  ],
};

const CONSTRAINTS: ConstraintsView = {
  analysedAt: '2026-07-01T10:00:00Z',
  scoreGlobal: '0hard/-30medium/0soft',
  postesNonPourvus: 0,
  faisabilite: null,
  hardScore: 0,
  contraintes: [EQUILIBRE],
  contraintesAdHocEnCause: [],
  scoreHorsPlancher: '0hard/-30medium/0soft',
  plancherMedium: 0,
  plancherSoft: 0,
  pivotEcarts: [
    { contrainte: 'equilibrerCharge', axe: 'ANIMATEUR', cle: 'a1', ecarts: 3 },
    { contrainte: 'equilibrerCharge', axe: 'STAND', cle: 'S1', ecarts: 2 },
  ],
  lecture: [],
};

const PAUSES: RapportPauses = {
  journeesAnalysees: 1,
  pausesDues: 1,
  relaisManquants: 1,
  coupuresRepasDues: 0,
  coupuresRepasManquantes: 0,
  journees: [
    {
      animateurId: 'a2',
      date: '2026-07-12',
      jour: 1,
      nomComplet: 'Anonyme',
      mineur: false,
      sequences: [
        {
          debut: '09:00',
          fin: '15:00',
          minutes: 360,
          pausesDues: [
            {
              debut: '14:00',
              fin: '14:20',
              heureLimite: '14:00',
              dureeMinutes: 20,
              standId: 'S2',
              standNom: 'Stand deux',
              creneauId: 7,
              relais: [],
              relaisDisponible: false,
              simultanee: false,
            },
          ],
        },
      ],
      pausesPlanifiees: [],
      coupuresRepas: [],
    },
  ],
  message: '',
};

function animateur(id: string, prenom: string, nom: string): Animateur {
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

const FIRST_STAND: Stand = {
  id: 'S1',
  nom: 'Stand un',
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
};

const CRENEAU_COURT: Creneau = {
  id: 42,
  jour: 1,
  date: '2026-07-12',
  heureDebut: '10:00',
  heureFin: '12:00',
};

/** One free seat on the short timeslot, and one person to place on it. */
const PLAN: PlanningEvenement = {
  animateurs: [animateur('a1', 'Alice', 'Martin')],
  postes: [{ id: 'P1', stand: FIRST_STAND, creneau: CRENEAU_COURT, animateur: null }],
  score: null,
};

describe('ProblemesPage — « Que faire ? »', () => {
  let fixture: ComponentFixture<ProblemesPage>;
  /** What `/api/feasibility` answers; a test may swap it before re-creating the page. */
  let feasibility: FeasibilityReport = FEASIBILITY;
  const api = {
    get: vi.fn((url: string) => {
      if (url === '/api/feasibility') {
        return Promise.resolve(feasibility);
      }
      if (url === '/api/pauses') {
        return Promise.resolve(PAUSES);
      }
      if (['/api/stands', '/api/animateurs', '/api/creneaux'].includes(url)) {
        return Promise.resolve([]);
      }
      if (url === '/api/planning/volumetrie') {
        return Promise.resolve({});
      }
      return Promise.resolve(CONSTRAINTS);
    }),
    getResponse: vi.fn(),
    getDansEdition: vi.fn(),
    getPreservingHttpError: vi.fn(),
    post: vi.fn(),
    postPreservingHttpError: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    postRaw: vi.fn(),
    downloadPost: vi.fn(),
    downloadGet: vi.fn(),
  };
  const placement = {
    choose: vi.fn(() => Promise.resolve({ animateurId: 'a1', keep: true })),
    place: vi.fn(() =>
      Promise.resolve({
        message: 'Alice Martin est placé(e) sur ce siège, et y restera au prochain calcul.',
        warning: null,
        lockError: '',
      }),
    ),
  };
  const planningState = {
    loadForDisplay: vi.fn(() => Promise.resolve(PLAN)),
    set: vi.fn(),
  };

  beforeEach(async () => {
    feasibility = FEASIBILITY;
    Object.values(api).forEach((stub) => stub.mockClear());
    placement.choose.mockClear();
    placement.place.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: '**', component: Target }]),
        { provide: ApiService, useValue: api },
        { provide: SeatPlacement, useValue: placement },
        { provide: PlanningStateService, useValue: planningState },
        {
          provide: SolverJobService,
          useValue: {
            activeJob: signal(null),
            editingLocked: signal(false),
            onResult: () => () => undefined,
          },
        },
      ],
    });
    fixture = TestBed.createComponent(ProblemesPage);
    await fixture.whenStable();
    fixture.detectChanges();
  });

  function cards(): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.probleme-card'));
  }

  function card(text: string): HTMLElement {
    return cards().find((candidate) => candidate.textContent?.includes(text))!;
  }

  // A feasible report may still carry warnings (a tight cap on days in a row):
  // « aucun problème de capacité » would contradict the card listed below it.
  it('says a feasible plan has points of attention when warnings remain', async () => {
    feasibility = {
      ...FEASIBILITY,
      feasible: true,
      manqueAnimateurs: 0,
      message: 'Le planning est réalisable. Point de vigilance : le plafond est à la limite.',
      causes: [{ ...FEASIBILITY.causes[0], type: 'PLAFOND_JOURS_CONSECUTIFS', manque: 0 }],
    };
    fixture = TestBed.createComponent(ProblemesPage);
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain(
      'Le planning est réalisable, avec 1 point(s) de vigilance listé(s) ci-dessous.',
    );
    expect(text).not.toContain('Aucun problème de capacité détecté');
  });

  it('gives every problem at least one action, with its explanation and its button', () => {
    expect(cards()).toHaveLength(3);
    for (const probleme of cards()) {
      expect(probleme.querySelector('.probleme-que-faire-titre')?.textContent).toContain(
        'Que faire ?',
      );
      const actions = probleme.querySelectorAll('.probleme-action');
      expect(actions.length).toBeGreaterThan(0);
      for (const action of Array.from(actions)) {
        expect(action.querySelector('.probleme-action-bouton')?.textContent?.trim()).not.toBe('');
        expect(action.querySelector('.probleme-action-explication')?.textContent?.trim()).not.toBe(
          '',
        );
      }
    }
  });

  // The acceptance of the Diagnostic: « Qui peut tenir ce siège ? » then
  // « Placer » fills the seat without leaving the page.
  it('asks who can hold the seat in place, and places the one chosen without leaving', async () => {
    const router = TestBed.inject(Router);
    const before = router.url;
    const bench = card('il manque 3 animateurs').querySelector<HTMLButtonElement>(
      'button.probleme-action-bouton',
    )!;
    expect(bench.textContent).toContain('Qui peut tenir ce siège ?');

    bench.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(placement.choose).toHaveBeenCalledWith(
      expect.objectContaining({
        posteId: 'P1',
        creneauId: 42,
        standId: 'S1',
        offerPlacement: true,
      }),
    );
    expect(placement.place).toHaveBeenCalledWith(
      'P1',
      42,
      { animateurId: 'a1', keep: true },
      'Alice Martin',
    );
    expect(router.url).toBe(before);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'et y restera au prochain calcul',
    );
    // The plan moved: the session's copy is dropped.
    expect(planningState.set).toHaveBeenCalledWith(null);
  });

  // « Qui peut remplacer ? »: the breach is on a7's seat. A free seat of the
  // same timeslot is not the question — the Planning page opens on a7's seat,
  // whose panel offers « Remplacer », and nobody is placed from here.
  it("opens the holder's own seat when the breach names them, never a free one", async () => {
    const router = TestBed.inject(Router);
    planningState.loadForDisplay.mockResolvedValueOnce({
      ...PLAN,
      postes: [
        ...PLAN.postes,
        {
          id: 'P2',
          stand: FIRST_STAND,
          creneau: CRENEAU_COURT,
          animateur: animateur('a7', 'Anonyme', 'Sept'),
        },
      ],
    });
    const page = fixture.componentInstance;

    await page['whoCanHold']({
      code: 'VOIR_BANC',
      libelle: 'Qui peut remplacer ?',
      explication: '',
      route: '/journee',
      queryParams: { creneau: '42', stand: 'S1', animateur: 'a7' },
      lang: 'fr',
      seat: { creneauId: 42, standId: 'S1', animateurId: 'a7' },
    });
    await fixture.whenStable();

    expect(placement.choose).not.toHaveBeenCalled();
    expect(router.url).toBe('/journee?creneau=42&stand=S1&animateur=a7');
  });

  it('says where a rule bites and on whom, each opening its screen', () => {
    const rule = card('Équilibre de la charge');
    const where = rule.querySelector<HTMLAnchorElement>('a.probleme-lieu')!;
    const qui = rule.querySelector<HTMLAnchorElement>('a.probleme-personne')!;

    expect(where.getAttribute('href')).toBe('/journee?stand=S1');
    expect(qui.getAttribute('href')).toBe('/animateurs/a1');
  });

  it('lowers the importance last, on the rule line of the rules screen', () => {
    const rule = card('Équilibre de la charge');
    const buttons = Array.from(rule.querySelectorAll('a.probleme-action-bouton'));
    const last = buttons.at(-1)!;

    expect(buttons[0].textContent).toContain('Voir la charge par personne');
    expect(last.textContent).toContain("Baisser l'importance");
    expect(last.getAttribute('href')).toBe('/regles?onglet=qualite&regle=equilibrerCharge');
  });

  it('gives the relay-less breaks their own gestures, positioned on the first break', () => {
    const pauses = card('Pauses sans relais');
    const links = Array.from(pauses.querySelectorAll('a.probleme-action-bouton')).map((link) =>
      link.getAttribute('href'),
    );
    expect(links).toEqual(['/ouvertures?vue=saisie&stand=S2', '/creneaux?edit=7']);
    // Written here and translated: no `lang` forcing French on them.
    expect(pauses.querySelector('a.probleme-action-bouton')!.getAttribute('lang')).toBeNull();
    // Où: the stand and the timeslot of the break; Qui: the person left alone.
    expect(pauses.querySelector('a.probleme-lieu')!.getAttribute('href')).toBe(
      '/journee?creneau=7&stand=S2',
    );
    expect(pauses.querySelector('a.probleme-personne')!.getAttribute('href')).toBe(
      '/animateurs/a2',
    );
  });

  it('marks the actions the server wrote as French, and names each block after its problem', () => {
    const shortfall = card('il manque 3 animateurs');
    expect(shortfall.querySelector('.probleme-action-bouton')!.getAttribute('lang')).toBe('fr');
    expect(shortfall.querySelector('.probleme-action-explication')!.getAttribute('lang')).toBe(
      'fr',
    );

    const heading = shortfall.querySelector('h2.probleme-titre')!;
    const block = shortfall.querySelector('section.probleme-que-faire')!;
    const names = block.getAttribute('aria-labelledby')!.split(' ');
    expect(names).toContain(heading.id);
    expect(names).toContain(block.querySelector('h3')!.id);
  });

  it('keeps the score in figures in a tooltip, the reading in sentences in front', () => {
    const root = fixture.nativeElement as HTMLElement;
    const info = root.querySelector('.probleme-score')!;

    expect(info.getAttribute('aria-label')).toContain('0hard/-30medium/0soft');
    expect(root.querySelector('.calendar-meta')?.textContent).not.toContain('medium');
  });

  /**
   * Every gesture but the bench is a plain link: following each one lands on
   * its address while nothing leaves for the server. The screens it opens keep
   * their own guards; they are not rendered here.
   */
  it('makes every other gesture a link, and following them sends no write', async () => {
    const root = fixture.nativeElement as HTMLElement;
    const router = TestBed.inject(Router);
    const links = Array.from(root.querySelectorAll<HTMLAnchorElement>('a.probleme-action-bouton'));
    expect(links.length).toBeGreaterThan(0);

    for (const link of links) {
      const target = link.getAttribute('href')!;
      link.click();
      await fixture.whenStable();
      expect(router.url).toBe(target);
    }
    for (const verb of [
      api.post,
      api.postPreservingHttpError,
      api.put,
      api.delete,
      api.postRaw,
      api.downloadPost,
    ]) {
      expect(verb).not.toHaveBeenCalled();
    }
  });
});

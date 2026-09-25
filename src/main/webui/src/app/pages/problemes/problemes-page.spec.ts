import { Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import {
  ConstraintsView,
  ConstraintView,
  FeasibilityReport,
  RapportPauses,
} from '../../core/models';
import { SolverJobService } from '../../core/solver-job.service';
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
          libelle: 'Voir qui pourrait venir',
          explication: 'Le banc de touche liste qui est libre sur ce créneau.',
          route: '/diagnostic',
          parametres: { onglet: 'banc', creneau: '42' },
        },
        {
          code: 'BAISSER_EFFECTIF',
          libelle: "Baisser l'effectif demandé",
          explication: 'Moins de places ouvertes ce jour-là.',
          route: '/ouvertures',
          parametres: { vue: 'saisie', q: 'S1' },
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
  remediation: 'La charge est inégale : baissez le poids si l’écart vous convient.',
  actif: true,
  protegee: false,
  legale: false,
  activeByDefault: true,
  dosable: true,
  poids: 1,
  score: '0hard/-30medium/0soft',
  matchCount: 3,
  violations: [],
  postesEvalues: null,
  plancher: null,
  references: [],
  actions: [
    {
      code: 'BAISSER_POIDS',
      libelle: 'Baisser son poids',
      explication: 'La charge est inégale : baissez le poids si l’écart vous convient.',
      route: '/constraints',
      parametres: { rule: 'equilibrerCharge' },
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
  pivotEcarts: [],
  lecture: [],
};

const PAUSES = {
  relaisManquants: 1,
  journees: [
    {
      date: '2026-07-12',
      nomComplet: 'Anonyme',
      sequences: [
        {
          pausesDues: [
            {
              debut: '14:00',
              fin: '14:20',
              standId: 'S2',
              standNom: 'Stand deux',
              creneauId: 7,
              relaisDisponible: false,
            },
          ],
        },
      ],
    },
  ],
} as unknown as RapportPauses;

describe('ProblemesPage — « Que faire ? »', () => {
  let fixture: ComponentFixture<ProblemesPage>;
  const api = {
    get: vi.fn(async (url: string) => {
      if (url === '/api/feasibility') {
        return FEASIBILITY;
      }
      if (url === '/api/pauses') {
        return PAUSES;
      }
      if (url === '/api/stands') {
        return [];
      }
      if (url === '/api/planning/volumetrie') {
        return {};
      }
      return CONSTRAINTS;
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

  beforeEach(async () => {
    Object.values(api).forEach((stub) => stub.mockClear());
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([{ path: '**', component: Target }]),
        { provide: ApiService, useValue: api },
        {
          provide: SolverJobService,
          useValue: { activeJob: signal(null), onResult: () => () => undefined },
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

  it('gives every problem at least one action, with its explanation and its button', () => {
    expect(cards()).toHaveLength(3);
    for (const card of cards()) {
      expect(card.querySelector('.probleme-que-faire-titre')?.textContent).toContain('Que faire ?');
      const actions = card.querySelectorAll('.probleme-action');
      expect(actions.length).toBeGreaterThan(0);
      for (const action of Array.from(actions)) {
        expect(action.querySelector('a.probleme-action-bouton')?.textContent?.trim()).not.toBe('');
        expect(action.querySelector('.probleme-action-explication')?.textContent?.trim()).not.toBe(
          '',
        );
      }
    }
  });

  it('opens the bench on the short timeslot first', () => {
    const shortfall = cards().find((card) => card.textContent?.includes('il manque 3 animateurs'))!;
    const first = shortfall.querySelector('a.probleme-action-bouton')!;
    expect(first.textContent).toContain('Voir qui pourrait venir');
    expect(first.getAttribute('href')).toBe('/diagnostic?onglet=banc&creneau=42');
  });

  it('opens the constraints page on the highlighted rule to lower its weight', () => {
    const rule = cards().find((card) => card.textContent?.includes('Équilibre de la charge'))!;
    const button = rule.querySelector('a.probleme-action-bouton')!;
    expect(button.textContent).toContain('Baisser son poids');
    expect(button.getAttribute('href')).toBe('/constraints?rule=equilibrerCharge');
  });

  it('gives the relay-less breaks their own gestures, positioned on the first break', () => {
    const pauses = cards().find((card) => card.textContent?.includes('Pauses sans relais'))!;
    const links = Array.from(pauses.querySelectorAll('a.probleme-action-bouton')).map((link) =>
      link.getAttribute('href'),
    );
    expect(links).toEqual(['/ouvertures?vue=saisie&stand=S2', '/creneaux?edit=7']);
    // Written here and translated: no `lang` forcing French on them.
    expect(pauses.querySelector('a.probleme-action-bouton')!.getAttribute('lang')).toBeNull();
  });

  it('marks the actions the server wrote as French, and names each block after its problem', () => {
    const shortfall = cards().find((card) => card.textContent?.includes('il manque 3 animateurs'))!;
    expect(shortfall.querySelector('a.probleme-action-bouton')!.getAttribute('lang')).toBe('fr');
    expect(shortfall.querySelector('.probleme-action-explication')!.getAttribute('lang')).toBe(
      'fr',
    );

    const heading = shortfall.querySelector('h2.probleme-titre')!;
    const block = shortfall.querySelector('section.probleme-que-faire')!;
    const names = block.getAttribute('aria-labelledby')!.split(' ');
    expect(names).toContain(heading.id);
    expect(names).toContain(block.querySelector('h3')!.id);
  });

  /**
   * What this proves, and no more: every gesture of « Que faire ? » is a plain
   * link — no button, no click handler of this page — and following each one
   * lands on its address while nothing leaves for the server. The screens it
   * opens keep their own guards; they are not rendered here.
   */
  it('offers only links, and following them navigates without sending a single write', async () => {
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelectorAll('.probleme-que-faire button')).toHaveLength(0);
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

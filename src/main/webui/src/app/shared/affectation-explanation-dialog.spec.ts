// A rendering test, against the project's taste for logic tests, because this
// dialog *is* its template: it is the screen that explains why the solver put
// someone on a seat, and nothing else in the application says the same thing
// twice. If it renders the wrong constraint, the wrong count or a stale
// suggestion, no other test — and no user — can tell.
//
// What the logic tests next door (`affectation-explanation-rules.spec.ts`)
// cannot see: which branch is on screen, and that the loaded explanation is the
// one being shown.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AffectationExplanationService } from '../core/affectation-explanation.service';
import {
  AffectationExplanation,
  Animateur,
  ContrainteImpact,
  HardMediumSoftScore,
  NiveauContrainte,
  PlanningEvenement,
  PosteAffectation,
  Stand,
  SuggestionReparation,
  SuggestionsReparation,
} from '../core/models';
import {
  AffectationExplanationDialog,
  AffectationExplanationDialogData,
} from './affectation-explanation-dialog';
import { SolverJobService } from '../core/solver-job.service';

function score(hardScore: number, mediumScore: number, softScore: number): HardMediumSoftScore {
  return { hardScore, mediumScore, softScore };
}

function impact(name: string, overrides: Partial<ContrainteImpact> = {}): ContrainteImpact {
  return {
    name,
    niveau: 'HARD' as NiveauContrainte,
    categorie: null,
    description: null,
    matchCount: 1,
    details: [],
    ...overrides,
  };
}

function animateur(id: string, prenom: string, nom: string): Animateur {
  return {
    id,
    prenom,
    nom,
    dateNaissance: '2000-01-01',
    manager: false,
    competences: { ambiance: 'REFERENT' },
    souhaits: [],
    joursIndisponibles: [],
  };
}

const STAND: Stand = {
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
  horaires: [],
};

const TITULAIRE = animateur('a1', 'Camille', 'Durand');
const CANDIDAT = animateur('a2', 'Alex', 'Martin');

const POSTE: PosteAffectation = { id: 'p1', stand: STAND, creneau: null, animateur: TITULAIRE };

const PLANNING: PlanningEvenement = {
  animateurs: [TITULAIRE, CANDIDAT],
  postes: [POSTE],
  score: null,
};

function explication(overrides: Partial<AffectationExplanation> = {}): AffectationExplanation {
  return {
    posteId: 'p1',
    animateurId: 'a1',
    score: score(-2, 0, -35),
    contraintesViolees: [],
    contraintesRespectees: [],
    ...overrides,
  };
}

function suggestion(
  animateurId: string,
  overrides: Partial<SuggestionReparation> = {},
): SuggestionReparation {
  return {
    animateurId,
    scoreApres: score(0, 0, -38),
    delta: score(2, 0, -3),
    violationsResolues: [],
    violationsIntroduites: [],
    ...overrides,
  };
}

function suggestions(overrides: Partial<SuggestionsReparation> = {}): SuggestionsReparation {
  return {
    posteId: 'p1',
    animateurActuelId: 'a1',
    scoreAvant: score(-2, 0, -35),
    contraintesVioleesAvant: [],
    candidatsEligibles: 1,
    candidatsEvalues: 1,
    plafond: 20,
    suggestions: [],
    ...overrides,
  };
}

interface ServiceStub {
  explique: ReturnType<typeof vi.fn>;
  suggererReparations: ReturnType<typeof vi.fn>;
  appliquerReparation: ReturnType<typeof vi.fn>;
}

function mount(
  service: Partial<ServiceStub>,
  data: Partial<AffectationExplanationDialogData> = {},
): { fixture: ComponentFixture<AffectationExplanationDialog>; close: ReturnType<typeof vi.fn> } {
  const close = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: AffectationExplanationService,
        useValue: {
          explique: vi.fn(async () => explication()),
          suggererReparations: vi.fn(async () => suggestions()),
          appliquerReparation: vi.fn(async () => undefined),
          ...service,
        },
      },
      { provide: MatDialogRef, useValue: { close } },
      { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      {
        provide: MAT_DIALOG_DATA,
        useValue: { poste: POSTE, planning: PLANNING, ...data },
      },
    ],
  });
  return { fixture: TestBed.createComponent(AffectationExplanationDialog), close };
}

/** Everything the dialog is currently showing, whitespace-normalised. */
function text(fixture: ComponentFixture<AffectationExplanationDialog>): string {
  return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ').trim();
}

function root(fixture: ComponentFixture<AffectationExplanationDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

/** A promise whose resolution this test controls, to observe the pending state. */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe('AffectationExplanationDialog', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows a spinner while the server has not answered yet, and no explanation', async () => {
    const pending = deferred<AffectationExplanation>();
    const { fixture } = mount({ explique: vi.fn(() => pending.promise) });
    await fixture.whenStable();

    expect(root(fixture).querySelector('mat-spinner')).not.toBeNull();
    expect(text(fixture)).not.toContain('Score global');

    pending.resolve(explication());
    // Awaiting the very promise the component awaited: our continuation is
    // queued behind its own, so the signals are written before we look. A bare
    // `whenStable()` here returns while the load is still pending, and a bare
    // `await Promise.resolve()` would not run the component's continuation at
    // all — zoneless schedules nothing on our behalf.
    await pending.promise;
    await fixture.whenStable();

    expect(root(fixture).querySelector('mat-spinner')).toBeNull();
    expect(text(fixture)).toContain('Score global');
  });

  it('names the seat it is explaining: the stand and its current occupant', async () => {
    const { fixture } = mount({});
    await fixture.whenStable();

    const titre = root(fixture).querySelector('h2')!.textContent!.replace(/\s+/g, ' ').trim();
    expect(titre).toBe('Loup-Garou — Camille Durand');
  });

  it('renders the score the server returned, not a hard-coded one', async () => {
    const { fixture } = mount({
      explique: vi.fn(async () => explication({ score: score(-7, -1, -12) })),
    });
    await fixture.whenStable();

    const ligne = root(fixture).querySelector('.affectation-explanation-score')!.textContent!;
    expect(ligne.replace(/\s+/g, ' ')).toContain('-7hard / -1medium / -12soft');
  });

  it('lists each violated constraint with its level badge and its per-match details', async () => {
    const { fixture } = mount({
      explique: vi.fn(async () =>
        explication({
          contraintesViolees: [
            impact('reposQuotidien', {
              niveau: 'HARD' as NiveauContrainte,
              description: 'Repos quotidien de 11 h',
              details: ['Camille Durand le 2026-07-14', 'Camille Durand le 2026-07-15'],
            }),
          ],
        }),
      ),
    });
    await fixture.whenStable();

    const affiche = text(fixture);
    expect(affiche).toContain('Contraintes non respectées pour ce poste');
    // The description wins over the raw constraint name when the server sends one.
    expect(affiche).toContain('Repos quotidien de 11 h');
    expect(affiche).not.toContain('reposQuotidien');
    expect(affiche).toContain('Camille Durand le 2026-07-14');
    expect(affiche).toContain('Camille Durand le 2026-07-15');
    expect(
      root(fixture).querySelector('.affectation-explanation-badge.niveau-HARD'),
    ).not.toBeNull();
  });

  it('falls back to the constraint name when the server sends no description', async () => {
    const { fixture } = mount({
      explique: vi.fn(async () => explication({ contraintesViolees: [impact('mineurApres22h')] })),
    });
    await fixture.whenStable();

    expect(text(fixture)).toContain('mineurApres22h');
  });

  it('says so explicitly when nothing is violated, instead of showing an empty list', async () => {
    const { fixture } = mount({});
    await fixture.whenStable();

    expect(text(fixture)).toContain('Aucun écart détecté sur ce poste');
    expect(root(fixture).querySelectorAll('.affectation-explanation-list')).toHaveLength(0);
  });

  it('counts the respected constraints from the list the server sent', async () => {
    const { fixture } = mount({
      explique: vi.fn(async () =>
        explication({ contraintesRespectees: [impact('c1'), impact('c2'), impact('c3')] }),
      ),
    });
    await fixture.whenStable();

    expect(
      root(fixture).querySelector('.affectation-explanation-respected-count')!.textContent,
    ).toContain('3');
  });

  it('shows the failure instead of an empty dialog when the explanation cannot be loaded', async () => {
    const { fixture } = mount({
      explique: vi.fn(async () => {
        throw new Error('poste introuvable');
      }),
    });
    await fixture.whenStable();

    expect(root(fixture).querySelector('[role="alert"]')!.textContent).toContain(
      'poste introuvable',
    );
    expect(root(fixture).querySelector('mat-spinner')).toBeNull();
    expect(text(fixture)).not.toContain('Score global');
  });

  it('closes on the Fermer button', async () => {
    const { fixture, close } = mount({});
    await fixture.whenStable();

    const boutons = Array.from(root(fixture).querySelectorAll('button'));
    const fermer = boutons.find((bouton) => bouton.textContent?.includes('Fermer'))!;
    fermer.click();

    expect(close).toHaveBeenCalled();
  });

  describe('assistant de réparation (issue #71)', () => {
    /** Clicks the button whose label contains `libelle`, and lets the handler settle. */
    async function cliquer(
      fixture: ComponentFixture<AffectationExplanationDialog>,
      libelle: string,
    ): Promise<void> {
      const bouton = Array.from(root(fixture).querySelectorAll('button')).find((each) =>
        each.textContent?.includes(libelle),
      )!;
      bouton.click();
      await fixture.whenStable();
    }

    it('searches nothing until asked: the endpoint costs one analysis per candidate', async () => {
      const suggererReparations = vi.fn(async () => suggestions());
      const { fixture } = mount({ suggererReparations });
      await fixture.whenStable();

      expect(suggererReparations).not.toHaveBeenCalled();
      expect(text(fixture)).toContain('Suggestions de réparation');
    });

    it('lists the viable replacements with the constraints each one settles', async () => {
      const { fixture } = mount({
        suggererReparations: vi.fn(async () =>
          suggestions({
            suggestions: [
              suggestion('a2', {
                violationsResolues: [
                  impact('pasDeChevauchementHoraire', { description: 'Pas de chevauchement' }),
                ],
              }),
            ],
          }),
        ),
      });
      await fixture.whenStable();
      await cliquer(fixture, 'Chercher des remplaçants viables');

      expect(text(fixture)).toContain('Alex Martin');
      expect(text(fixture)).toContain('Pas de chevauchement');
    });

    it('says so plainly when no candidate can take the seat without breaking a hard rule', async () => {
      const { fixture } = mount({
        suggererReparations: vi.fn(async () => suggestions({ suggestions: [] })),
      });
      await fixture.whenStable();
      await cliquer(fixture, 'Chercher des remplaçants viables');

      expect(text(fixture)).toContain('Aucun remplacement possible');
    });

    /**
     * The dangerous case: a truncated list read as an exhaustive one would let
     * an operator conclude "nobody else can do it" from twenty candidates out
     * of a hundred and thirty-seven.
     */
    it('warns that a truncated search is not an exhaustive answer', async () => {
      const { fixture } = mount({
        suggererReparations: vi.fn(async () =>
          suggestions({
            candidatsEligibles: 137,
            candidatsEvalues: 20,
            suggestions: [suggestion('a2')],
          }),
        ),
      });
      await fixture.whenStable();
      await cliquer(fixture, 'Chercher des remplaçants viables');

      expect(text(fixture)).toContain('pas une réponse exhaustive');
    });

    it('stays silent about truncation when the whole eligible pool was evaluated', async () => {
      const { fixture } = mount({
        suggererReparations: vi.fn(async () =>
          suggestions({
            candidatsEligibles: 2,
            candidatsEvalues: 2,
            suggestions: [suggestion('a2')],
          }),
        ),
      });
      await fixture.whenStable();
      await cliquer(fixture, 'Chercher des remplaçants viables');

      expect(text(fixture)).not.toContain('pas une réponse exhaustive');
    });

    it('applies a suggestion and closes with what changed, so the calendar can reload', async () => {
      const appliquerReparation = vi.fn(async () => undefined);
      const { fixture, close } = mount({
        suggererReparations: vi.fn(async () => suggestions({ suggestions: [suggestion('a2')] })),
        appliquerReparation,
      });
      await fixture.whenStable();
      await cliquer(fixture, 'Chercher des remplaçants viables');
      await cliquer(fixture, 'Appliquer');

      expect(appliquerReparation).toHaveBeenCalledWith('p1', 'a2');
      expect(close).toHaveBeenCalledWith({ posteId: 'p1', animateurId: 'a2' });
    });

    it('keeps the dialog open and shows why when applying is refused', async () => {
      const { fixture, close } = mount({
        suggererReparations: vi.fn(async () => suggestions({ suggestions: [suggestion('a2')] })),
        appliquerReparation: vi.fn(async () => {
          throw new Error('Ce poste est verrouillé');
        }),
      });
      await fixture.whenStable();
      await cliquer(fixture, 'Chercher des remplaçants viables');
      await cliquer(fixture, 'Appliquer');

      expect(close).not.toHaveBeenCalled();
      expect(text(fixture)).toContain('Ce poste est verrouillé');
    });

    it('reports a failed search without wiping the explanation already on screen', async () => {
      const { fixture } = mount({
        suggererReparations: vi.fn(async () => {
          throw new Error('recherche impossible');
        }),
      });
      await fixture.whenStable();
      await cliquer(fixture, 'Chercher des remplaçants viables');

      expect(text(fixture)).toContain('recherche impossible');
      expect(text(fixture)).toContain('Score global');
    });
  });
});

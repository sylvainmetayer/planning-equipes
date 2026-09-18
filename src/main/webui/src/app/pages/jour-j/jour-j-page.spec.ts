// The event-day screen, rendered. The wording is pinned down next door in
// `jour-j-wording.spec.ts`; what is checked here is the flow the issue asks
// for and the two promises it makes:
//
//   - marking somebody absent goes straight to the seats it freed and asks who
//     can take them, without the operator navigating anywhere;
//   - applying a replacement reuses the repair assistant's own write, so no
//     solve is ever started from this page.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
import { JourJService } from '../../core/jour-j.service';
import { NotificationService } from '../../core/notification.service';
import type { AbsenceMarquee, EtatJourJ, SuggestionsReparation } from '../../core/models';
import { JourJPage } from './jour-j-page';

function etat(overrides: Partial<EtatJourJ> = {}): EtatJourJ {
  return {
    date: '2026-07-08',
    maintenant: '2026-07-08T13:30:00',
    creneauxDuJour: 2,
    creneauxRestants: [
      { id: 2, date: '2026-07-08', heureDebut: '14:00:00', heureFin: '18:00:00', enCours: false },
    ],
    animateursDeService: [
      { animateurId: 'A1', nomAffiche: 'Alice Referente', postesRestants: 1, absent: false },
    ],
    postesAPourvoir: [],
    absences: [],
    animateurs: [
      { animateurId: 'A1', nomAffiche: 'Alice Referente' },
      { animateurId: 'A2', nomAffiche: 'Bruno Autonome' },
    ],
    consigne: null,
    ...overrides,
  };
}

const posteLibere = {
  posteId: 'P2',
  standId: 'STAND-STRAT',
  standNom: 'Stand stratégie',
  creneauId: 2,
  heureDebut: '14:00:00',
  heureFin: '18:00:00',
  verrouille: false,
};

function marquee(): AbsenceMarquee {
  return {
    animateurId: 'A1',
    nomAffiche: 'Alice Referente',
    entrees: [
      {
        contrainteId: 'absence-jour-j-A1-2',
        creneauId: 2,
        heureDebut: '14:00:00',
        heureFin: '18:00:00',
        raison: 'Absent le 2026-07-08 (mode jour J)',
        creeParUtilisateurId: 'admin',
        creeLe: '2026-07-08T11:30:00Z',
        annulable: true,
      },
    ],
    postesLiberes: [posteLibere],
  };
}

function suggestions(): SuggestionsReparation {
  return {
    posteId: 'P2',
    animateurActuelId: null,
    scoreAvant: { hardScore: -1, mediumScore: 0, softScore: 0 },
    contraintesVioleesAvant: [],
    candidatsEligibles: 137,
    candidatsEvalues: 20,
    plafond: 20,
    suggestions: [
      {
        animateurId: 'A2',
        scoreApres: { hardScore: 0, mediumScore: 0, softScore: -2 },
        delta: { hardScore: 1, mediumScore: 0, softScore: -2 },
        violationsResolues: [],
        violationsIntroduites: [],
      },
    ],
  };
}

describe('JourJPage', () => {
  let fixture: ComponentFixture<JourJPage>;
  let jourJ: {
    etat: ReturnType<typeof vi.fn>;
    marquerAbsent: ReturnType<typeof vi.fn>;
    annulerAbsence: ReturnType<typeof vi.fn>;
    suggestions: ReturnType<typeof vi.fn>;
    apercuPublication: ReturnType<typeof vi.fn>;
  };
  let reparations: { appliquerReparation: ReturnType<typeof vi.fn> };
  let notify: ReturnType<typeof vi.fn>;

  async function rendre(premierEtat: EtatJourJ, apresAbsence?: EtatJourJ): Promise<void> {
    const etats = apresAbsence
      ? [premierEtat, apresAbsence, apresAbsence, apresAbsence]
      : [premierEtat];
    let appel = 0;
    jourJ = {
      etat: vi.fn(async () => etats[Math.min(appel++, etats.length - 1)]),
      marquerAbsent: vi.fn(async () => marquee()),
      annulerAbsence: vi.fn(async () => undefined),
      suggestions: vi.fn(async () => suggestions()),
      apercuPublication: vi.fn(async () => ({
        jamaisPublie: false,
        planVide: false,
        solveEnCours: false,
        dernierePublicationLe: null,
        nombreConcernes: 3,
        destinataires: [],
      })),
    };
    reparations = { appliquerReparation: vi.fn(async () => undefined) };
    notify = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: JourJService, useValue: jourJ },
        { provide: AffectationExplanationService, useValue: reparations },
        { provide: NotificationService, useValue: { notify } },
      ],
    });
    fixture = TestBed.createComponent(JourJPage);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function bouton(libelle: string): HTMLButtonElement {
    const trouve = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((each) => (each.textContent ?? '').includes(libelle));
    expect(trouve, `bouton « ${libelle} » absent`).toBeDefined();
    return trouve as HTMLButtonElement;
  }

  beforeEach(async () => {
    await rendre(etat());
  });

  /**
   * The screen is under development and, unlike the other screens on trial, it
   * writes. The default banner wording ("data entered here may still change")
   * would be too gentle: what is pinned here is that the warning says the screen
   * acts, and on what.
   */
  it('warns that it writes to the saved plan, in its own words', () => {
    const banniere = (fixture.nativeElement as HTMLElement).querySelector(
      '.work-in-progress-banner',
    );
    expect(banniere).not.toBeNull();
    expect(banniere!.textContent).toContain('il agit');
    expect(banniere!.textContent).toContain('planning enregistré');
    expect(banniere!.textContent).toContain('ne sont pas encore garantis');
  });

  it('shows who is on duty over the remaining timeslots', () => {
    expect(text()).toContain('Alice Referente');
    expect(text()).toContain('14:00 – 18:00');
  });

  /** Issue #245: a reminder with a link, never a send button inside an emergency screen. */
  it('reminds how many people are waiting for a publication, without offering to publish', () => {
    expect(text()).toContain('3');
    expect(text()).toContain('publi');
    const send = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (each) => /publier|envoyer/i.test(each.textContent ?? ''),
    );
    expect(send).toBeUndefined();
  });

  it('asks for a confirmation before writing an absence', async () => {
    bouton('Marquer absent').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(jourJ.marquerAbsent).not.toHaveBeenCalled();
    expect(text()).toContain("Confirmer l'absence");
  });

  it('marks the absence and immediately looks for a replacement on each freed seat', async () => {
    await rendre(etat(), etat({ postesAPourvoir: [posteLibere] }));

    bouton('Marquer absent').click();
    await fixture.whenStable();
    fixture.detectChanges();
    bouton("Confirmer l'absence").click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(jourJ.marquerAbsent).toHaveBeenCalledWith('A1', '');
    expect(jourJ.suggestions).toHaveBeenCalledWith('P2');
    expect(text()).toContain('Stand stratégie');
  });

  /**
   * The plafond, said out loud. A list of 20 shown bare would read as "there is
   * nobody else", which is the failure mode the issue explicitly names.
   */
  it('says the suggestion list is capped and not the whole pool', async () => {
    await rendre(etat({ postesAPourvoir: [posteLibere] }));

    bouton('Trouver un remplaçant').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(text()).toContain('20');
    expect(text()).toContain('137');
    expect(text()).toContain('pas tout le vivier');
  });

  /**
   * The acceptance criterion of the issue: one tap, one seat, and the write is
   * the repair assistant's surgical UPDATE — nothing on this page ever posts to
   * a solve endpoint.
   */
  it('applies a replacement through the repair assistant, starting no solve', async () => {
    await rendre(etat({ postesAPourvoir: [posteLibere] }));
    bouton('Trouver un remplaçant').click();
    await fixture.whenStable();
    fixture.detectChanges();
    // Named from the roster: A2 works nowhere on the remaining timeslots, which
    // is exactly why they are the best replacement.
    expect(text()).toContain('Bruno Autonome');

    bouton('Affecter').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(reparations.appliquerReparation).toHaveBeenCalledWith('P2', 'A2');
    expect(notify).toHaveBeenCalledWith(
      expect.objectContaining({ variant: 'success', message: expect.stringContaining('republié') }),
    );
  });

  /**
   * The write is a surgical UPDATE that checks locks and nothing else, so a list
   * computed against the previous plan is enough to hand the same person two
   * seats on the same hour — an overlap this screen would never report. Every
   * other list is therefore dropped, and searched again.
   */
  it('never leaves a suggestion list that was computed before the write', async () => {
    const autrePoste = { ...posteLibere, posteId: 'P3', standNom: 'Homme-jeu' };
    await rendre(etat({ postesAPourvoir: [posteLibere, autrePoste] }));
    for (const bloc of Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).filter((each) => (each.textContent ?? '').includes('Trouver un remplaçant'))) {
      bloc.click();
      await fixture.whenStable();
      fixture.detectChanges();
    }
    expect(jourJ.suggestions).toHaveBeenCalledTimes(2);

    bouton('Affecter').click();
    await fixture.whenStable();
    fixture.detectChanges();

    // The other seat's list was thrown away and asked for again — never shown
    // stale.
    expect(jourJ.suggestions).toHaveBeenCalledTimes(3);
    expect(jourJ.suggestions).toHaveBeenLastCalledWith('P3');
  });

  /** A lock is refused server-side, so the button is not offered at all. */
  it('explains a locked seat instead of offering a search that would be refused', async () => {
    await rendre(etat({ postesAPourvoir: [{ ...posteLibere, verrouille: true }] }));

    expect(text()).toContain('verrou');
    const find = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (each) => (each.textContent ?? '').includes('Trouver un remplaçant'),
    );
    expect(find).toBeUndefined();
  });

  /**
   * A contradiction with an existing forced assignment, or a locked seat: the
   * server names both sides and the page shows that message whole, with no
   * timeout — it is the only place the operator will read it.
   */
  it('keeps a refusal on screen, message and all', async () => {
    jourJ.marquerAbsent = vi.fn(async () => {
      throw new Error('La contrainte FORCE-1 force A1 sur le créneau …');
    });

    bouton('Marquer absent').click();
    await fixture.whenStable();
    fixture.detectChanges();
    bouton("Confirmer l'absence").click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(notify).toHaveBeenCalledWith(
      expect.objectContaining({
        variant: 'error',
        timeout: 0,
        message: expect.stringContaining('FORCE-1'),
      }),
    );
  });

  /**
   * An exception naming several animateurs is not this screen's to undo. Offering
   * the block button anyway meant answering "Annulation impossible" on a control
   * the screen had just proposed.
   */
  it('does not offer to undo an absence it cannot undo', async () => {
    await rendre(
      etat({
        absences: [
          {
            animateurId: 'A1',
            nomAffiche: 'Alice Referente',
            entrees: [
              {
                contrainteId: 'EXCEPTION-PARTAGEE',
                creneauId: 2,
                heureDebut: '14:00:00',
                heureFin: '18:00:00',
                raison: null,
                creeParUtilisateurId: 'admin',
                creeLe: '2026-07-08T11:30:00Z',
                annulable: false,
              },
            ],
          },
        ],
      }),
    );

    const cancel = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((each) => (each.textContent ?? '').includes("Annuler toute l'absence"));
    expect(cancel).toBeUndefined();
    expect(text()).toContain('Ajustement partagé');
  });

  it('cancels one timeslot of an absence without touching the others', async () => {
    await rendre(
      etat({
        absences: [
          {
            animateurId: 'A1',
            nomAffiche: 'Alice Referente',
            entrees: [
              {
                contrainteId: 'absence-jour-j-A1-2',
                creneauId: 2,
                heureDebut: '14:00:00',
                heureFin: '18:00:00',
                raison: 'Absent (mode jour J)',
                creeParUtilisateurId: 'admin',
                creeLe: '2026-07-08T11:30:00Z',
                annulable: true,
              },
            ],
          },
        ],
      }),
    );

    bouton('Annuler ce créneau').click();
    await fixture.whenStable();

    expect(jourJ.annulerAbsence).toHaveBeenCalledWith('A1', undefined, 2);
  });
});

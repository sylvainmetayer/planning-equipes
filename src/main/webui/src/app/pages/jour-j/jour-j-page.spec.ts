// Aujourd'hui, the event day's hub, rendered. The wording is pinned down next
// door in `jour-j-wording.spec.ts`; what is checked here is the flow the issue
// asks for and the promises it makes:
//
//   - somebody is found by a search, never in a list of 86 full buttons;
//   - marking somebody absent goes straight to the seats it freed and asks who
//     can take them, without the operator navigating anywhere;
//   - a new hole and a hole of the published plan are told apart;
//   - applying a replacement reuses the repair assistant's own write, so no
//     solve is ever started from this page;
//   - « Prévenir les N personnes » publishes to those people and nobody else.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
import { AffichageMuralApi } from '../../core/api/affichage-mural-api';
import { JourJService } from '../../core/jour-j.service';
import { NotificationService } from '../../core/notification.service';
import type {
  AbsenceMarquee,
  EtatJourJ,
  PosteAPourvoir,
  SuggestionsReparation,
} from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { JourJPage } from './jour-j-page';
import { SolverJobService } from '../../core/solver-job.service';

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
      { animateurId: 'A1', nomAffiche: 'Alice Referente', telephone: '06 11 22 33 44' },
      { animateurId: 'A2', nomAffiche: 'Bruno Autonome', telephone: '06 55 66 77 88' },
    ],
    consigne: null,
    signalements: [],
    jourNumero: 5,
    standsOuverts: 60,
    alertes: [],
    aPrevenir: [],
    echangesAArbitrer: 0,
    ...overrides,
  };
}

const posteLibere: PosteAPourvoir = {
  posteId: 'P2',
  standId: 'STAND-STRAT',
  standNom: 'Stand stratégie',
  creneauId: 2,
  heureDebut: '14:00:00',
  heureFin: '18:00:00',
  verrouille: false,
  nouveau: true,
  resteDuCreneau: false,
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
    prevenir: ReturnType<typeof vi.fn>;
  };
  let reparations: { applyRepair: ReturnType<typeof vi.fn> };
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
      prevenir: vi.fn(async () => ({
        snapshotId: 9,
        publieLe: '2026-07-08T11:35:00Z',
        envoyes: 2,
        sansEmail: [],
        echecs: [],
        differes: [],
      })),
    };
    reparations = { applyRepair: vi.fn(async () => undefined) };
    notify = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: JourJService, useValue: jourJ },
        { provide: AffectationExplanationService, useValue: reparations },
        { provide: NotificationService, useValue: { notify } },
        { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
        { provide: ConfirmService, useValue: { ask: vi.fn(async () => true) } },
        { provide: AffichageMuralApi, useValue: { list: vi.fn(async () => []) } },
      ],
    });
    fixture = TestBed.createComponent(JourJPage);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return racine().textContent ?? '';
  }

  function boutons(libelle: string): HTMLButtonElement[] {
    return Array.from(racine().querySelectorAll('button')).filter((each) =>
      (each.textContent ?? '').includes(libelle),
    );
  }

  function bouton(libelle: string): HTMLButtonElement {
    const trouve = boutons(libelle)[0];
    expect(trouve, `bouton « ${libelle} » absent`).toBeDefined();
    return trouve;
  }

  async function cliquer(libelle: string): Promise<void> {
    bouton(libelle).click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** Types in the search field, as a thumb would. */
  async function search(texte: string): Promise<void> {
    const champ = racine().querySelector('input[type=search]') as HTMLInputElement;
    champ.value = texte;
    champ.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await rendre(etat());
  });

  /** The work-in-progress banner is gone: the screen is the day's hub now. */
  it('states the day at a glance, with no work-in-progress banner', () => {
    expect(racine().querySelector('.work-in-progress-banner')).toBeNull();
    expect(text()).toContain('J5 · 60 stands ouverts · 1 vacation restante');
    expect(text()).toContain('13:30');
  });

  /** 153 people are found by a search: nobody is listed, no button is shown, before one. */
  it('lists nobody until a name is searched, then the person with their phone', async () => {
    expect(boutons('Marquer absent')).toHaveLength(0);

    await search('alice');

    expect(text()).toContain('Alice Referente');
    const tel = racine().querySelector('a[href^="tel:"]');
    expect(tel?.getAttribute('href')).toBe('tel:06 11 22 33 44');
    expect(boutons('Marquer absent')).toHaveLength(1);
  });

  it('asks for a confirmation before writing an absence', async () => {
    await search('alice');
    await cliquer('Marquer absent');

    expect(jourJ.marquerAbsent).not.toHaveBeenCalled();
    expect(text()).toContain("Confirmer l'absence");
  });

  it('marks the absence and immediately looks for a replacement on each freed seat', async () => {
    await rendre(etat(), etat({ postesAPourvoir: [posteLibere] }));

    await search('alice');
    await cliquer('Marquer absent');
    await cliquer("Confirmer l'absence");

    expect(jourJ.marquerAbsent).toHaveBeenCalledWith('A1', '');
    expect(jourJ.suggestions).toHaveBeenCalledWith('P2');
    expect(text()).toContain('Stand stratégie');
  });

  /**
   * The timeslot under way: the rest of the split seat is offered as such, and
   * three taps — mark absent, confirm, assign — repair it.
   */
  it('repairs the rest of the timeslot under way in three taps', async () => {
    const reste: PosteAPourvoir = {
      ...posteLibere,
      posteId: 'P2~0920',
      heureDebut: '09:20:00',
      heureFin: '12:00:00',
      resteDuCreneau: true,
    };
    await rendre(etat(), etat({ postesAPourvoir: [reste] }));
    jourJ.marquerAbsent = vi.fn(async () => ({ ...marquee(), postesLiberes: [reste] }));

    await search('alice');
    await cliquer('Marquer absent');
    await cliquer("Confirmer l'absence");
    expect(text()).toContain('Remplacer sur le reste du créneau');
    await cliquer('Affecter');

    expect(reparations.applyRepair).toHaveBeenCalledWith('P2~0920', 'A2');
  });

  /** « Nouveau depuis ce matin » vs « Places vides connues ». */
  it('tells a hole opened this morning from a hole of the published plan', async () => {
    const connu: PosteAPourvoir = {
      ...posteLibere,
      posteId: 'P9',
      standNom: 'Homme-jeu',
      nouveau: false,
    };
    await rendre(etat({ postesAPourvoir: [posteLibere, connu] }));

    const nouveau = racine().querySelector('#aujourdhui-nouveau')?.textContent ?? '';
    const connues = racine().querySelector('#aujourdhui-connues')?.textContent ?? '';
    expect(nouveau).toContain('Stand stratégie');
    expect(nouveau).not.toContain('Homme-jeu');
    expect(connues).toContain('Homme-jeu');
    expect(connues).toContain('Qui peut tenir ce siège ?');
    expect(racine().querySelectorAll('.aujourdhui-connu')).toHaveLength(1);
  });

  /**
   * The plafond, said out loud. A list of 20 shown bare would read as "there is
   * nobody else", which is the failure mode the issue explicitly names.
   */
  it('says the suggestion list is capped and not the whole pool', async () => {
    await rendre(etat({ postesAPourvoir: [posteLibere] }));

    await cliquer('Remplacer');

    expect(text()).toContain('20');
    expect(text()).toContain('137');
    expect(text()).toContain('pas tout le vivier');
  });

  /**
   * One tap, one seat, and the write is the repair assistant's surgical UPDATE
   * — nothing on this page ever posts to a solve endpoint. The replacement is
   * named from the roster, with the number to call them on.
   */
  it('applies a replacement through the repair assistant, starting no solve', async () => {
    await rendre(etat({ postesAPourvoir: [posteLibere] }));
    await cliquer('Remplacer');
    expect(text()).toContain('Bruno Autonome');
    expect(text()).toContain('06 55 66 77 88');

    await cliquer('Affecter');

    expect(reparations.applyRepair).toHaveBeenCalledWith('P2', 'A2');
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ variant: 'success' }));
  });

  /** After a replacement: « Prévenir les N personnes », to them and nobody else. */
  it('warns the people whose schedule moved, and only them', async () => {
    await rendre(etat({ aPrevenir: ['A1', 'A2'] }));

    expect(text()).toContain('2 personnes ont un planning différent');
    await cliquer('Prévenir les 2 personnes');

    expect(jourJ.prevenir).toHaveBeenCalledWith(['A1', 'A2']);
  });

  it('offers no send when everybody has the last version', () => {
    expect(boutons('Prévenir')).toHaveLength(0);
    expect(text()).toContain('dernière version');
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
    for (const bloc of boutons('Remplacer')) {
      bloc.click();
      await fixture.whenStable();
      fixture.detectChanges();
    }
    expect(jourJ.suggestions).toHaveBeenCalledTimes(2);

    await cliquer('Affecter');

    // The other seat's list was thrown away and asked for again — never shown
    // stale.
    expect(jourJ.suggestions).toHaveBeenCalledTimes(3);
    expect(jourJ.suggestions).toHaveBeenLastCalledWith('P3');
  });

  /** A lock is refused server-side, so the button is not offered at all. */
  it('explains a locked seat instead of offering a search that would be refused', async () => {
    await rendre(etat({ postesAPourvoir: [{ ...posteLibere, verrouille: true }] }));

    expect(text()).toContain('verrou');
    expect(boutons('Remplacer')).toHaveLength(0);
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

    await search('alice');
    await cliquer('Marquer absent');
    await cliquer("Confirmer l'absence");

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

    expect(boutons("Annuler toute l'absence")).toHaveLength(0);
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

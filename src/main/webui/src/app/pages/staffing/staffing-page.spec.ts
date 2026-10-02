// Loading / error / empty / data states of the staffing-need screen, plus the
// derived values the template binds to. The component is created but never
// rendered, so this stays a logic test (the project favours those over full
// DOM rendering).
//
// These four states are exactly what a migration to `httpResource()` would
// re-implement, which is why they are pinned here first.

import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Location } from '@angular/common';
import { provideRouter, Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { toError } from '../../core/api.service';
import { AnalysesApi } from '../../core/api/analyses-api';
import { RealiseApi } from '../../core/api/realise-api';
import {
  CompetenceStaffing,
  JourStaffing,
  PreviousEdition,
  SemaineStaffing,
  StaffingSummary,
  StaffingVerification,
  TypologieStaffing,
} from '../../core/models';
import { StaffingPage } from './staffing-page';

/** No earlier edition left a measure: the Besoin tab shows no previous column. */
const NO_PREVIOUS_EDITION: PreviousEdition = {
  available: false,
  editionId: null,
  editionNom: null,
  firstDay: null,
  lastDay: null,
  countedDays: null,
  frozenAt: null,
  event: null,
  byTypologie: [],
};

/** A promise whose settlement the test drives, to observe the in-flight state. */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

function jour(overrides: Partial<JourStaffing> = {}): JourStaffing {
  return {
    date: '2026-08-01',
    jour: 1,
    standsOuverts: 12,
    sieges: 40,
    heures: 200,
    picSimultane: 18,
    picAvecPause: 22,
    picRepas: 0,
    minimumJour: 22,
    disponibles: 40,
    absentsMax: 0,
    majeursRequis: 0,
    ...overrides,
  };
}

function semaine(overrides: Partial<SemaineStaffing> = {}): SemaineStaffing {
  return {
    semaine: '2026-W31',
    debut: '2026-07-27',
    jours: 7,
    joursTravaillables: 6,
    heures: 1400,
    capaciteHeuresParAnimateur: 48,
    chargeTotal: 30,
    joursPersonne: 154,
    rotationTotal: 26,
    budgetIndisponibilites: 2,
    indisponibilitesAuDela: 0,
    ...overrides,
  };
}

function typologie(overrides: Partial<TypologieStaffing> = {}): TypologieStaffing {
  return {
    typologie: 'ESCAPE',
    label: 'Escape game',
    ninja: false,
    sieges: 12,
    heures: 60,
    nombreSemaines: 1,
    picSimultane: 4,
    picAvecPause: 6,
    picRepas: 0,
    chargeTotal: 3,
    rotationTotal: 4,
    enchainementTotal: 4,
    plafondCreneaux: null,
    minimumPlafond: 0,
    minimumTotal: 6,
    borneRetenue: 'PIC_AVEC_PAUSE',
    specialistes: 6,
    manque: 0,
    ...overrides,
  };
}

function competence(overrides: Partial<CompetenceStaffing> = {}): CompetenceStaffing {
  return {
    parTypologie: [typologie()],
    polyvalents: 3,
    siegesNonAttribues: 0,
    siegesReservesAuxPolyvalents: 0,
    manqueTotal: 0,
    manquePolyvalents: 0,
    animateursTotal: 40,
    typologieNinjaDefinie: true,
    planchersCumules: 6,
    ...overrides,
  };
}

function summary(overrides: Partial<StaffingSummary> = {}): StaffingSummary {
  return {
    parJour: [jour()],
    parSemaine: [semaine()],
    semaineCritique: semaine(),
    picSimultane: 18,
    picAvecPause: 22,
    picRepas: 0,
    jourCritique: jour(),
    totalDemandeHeures: 1200,
    nombreSemaines: 3,
    capaciteHeuresParAnimateur: 48,
    chargeTotal: 14,
    rotationTotal: 20,
    minimumTotal: 22,
    borneRetenue: 'PIC_AVEC_PAUSE',
    minimumAvecIndisponibilites: 22,
    indisponibilitesDeclarees: false,
    enchainementTotal: 20,
    joursConsecutifsMax: null,
    effectifReference: 22,
    majeursMin: 15,
    mineursMax: 7,
    dureeHebdomadaireMaxMinutes: 2880,
    dureeQuotidienneMaxMinutes: 600,
    joursTravaillesMaxParSemaine: 6,
    parCompetence: competence(),
    referentielsManquants: [],
    ...overrides,
  };
}

/** One check by a solve, running unless a test says otherwise. */
function verification(overrides: Partial<StaffingVerification> = {}): StaffingVerification {
  return {
    id: 1,
    etat: 'EN_COURS',
    effectif: 22,
    majeurs: 22,
    mineurs: 0,
    sieges: 40,
    lanceeLe: '2026-10-01T10:00:00Z',
    plafondSecondes: 600,
    reglesEnDefaut: [],
    ...overrides,
  };
}

function createPage(): StaffingPage {
  return TestBed.createComponent(StaffingPage).componentInstance;
}

describe('StaffingPage', () => {
  const analysesApi = {
    staffing: vi.fn(),
    // The « avant » margin column and the « À former » section, read beside the need.
    margin: vi.fn(),
    trainingPlan: vi.fn(() => Promise.resolve(null)),
    exportTrainingPlan: vi.fn(),
    // The check by a solve: none run yet unless a test says otherwise.
    staffingVerification: vi.fn(),
    verifyStaffing: vi.fn(),
  };

  // No earlier edition left a measure, unless a test says otherwise.
  const realiseApi = {
    previousEdition: vi.fn((): Promise<PreviousEdition> => Promise.resolve(NO_PREVIOUS_EDITION)),
  };

  beforeEach(() => {
    realiseApi.previousEdition.mockReset();
    realiseApi.previousEdition.mockResolvedValue(NO_PREVIOUS_EDITION);
    analysesApi.staffing.mockReset();
    analysesApi.staffing.mockResolvedValue(summary());
    analysesApi.margin.mockReset();
    analysesApi.margin.mockResolvedValue(null);
    analysesApi.staffingVerification.mockReset();
    analysesApi.staffingVerification.mockResolvedValue(null);
    analysesApi.verifyStaffing.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AnalysesApi, useValue: analysesApi },
        { provide: RealiseApi, useValue: realiseApi },
      ],
    });
  });

  describe('loading state', () => {
    // The resource reports `loading` from the moment it exists, before its
    // loader has even run: the first paint shows a progress bar and not an
    // empty table.
    it('is loading while the first request is in flight, with nothing to show yet', () => {
      analysesApi.staffing.mockReturnValue(deferred<StaffingSummary>().promise);

      const page = createPage();

      expect(page['loading']()).toBe(true);
      expect(page['summary']()).toBeNull();
    });

    it('stops loading once the summary lands', async () => {
      const pending = deferred<StaffingSummary>();
      analysesApi.staffing.mockReturnValue(pending.promise);
      const page = createPage();

      pending.resolve(summary({ minimumTotal: 33 }));
      await vi.waitFor(() => expect(page['loading']()).toBe(false));

      expect(page['summary']()?.minimumTotal).toBe(33);
    });

    it('reads the summary from the server on creation instead of computing it in the browser', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page['loading']()).toBe(false));

      expect(analysesApi.staffing).toHaveBeenCalledOnce();
    });
  });

  describe('error state', () => {
    it('shows the failure message and stops loading', async () => {
      analysesApi.staffing.mockRejectedValue(new Error('Référentiel incomplet.'));

      const page = createPage();
      await vi.waitFor(() => expect(page['loading']()).toBe(false));

      expect(page['error']()).toContain('Référentiel incomplet.');
      expect(page['summary']()).toBeNull();
    });

    it('clears the error once a later load succeeds', async () => {
      analysesApi.staffing.mockRejectedValueOnce(new Error('Référentiel incomplet.'));
      const page = createPage();
      await vi.waitFor(() => expect(page['error']()).not.toBe(''));

      analysesApi.staffing.mockResolvedValue(summary());
      page['staffing'].reload();
      await vi.waitFor(() => expect(page['error']()).toBe(''));

      expect(page['summary']()).not.toBeNull();
    });

    // Deliberate, and different from the hours screen: the error is cleared on
    // success, never when a load starts. A failing refresh therefore keeps the
    // previous summary on screen behind its message rather than blanking it.
    it('keeps the previous summary visible when a refresh fails', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());

      analysesApi.staffing.mockRejectedValue(new Error('Référentiel incomplet.'));
      page['staffing'].reload();
      await vi.waitFor(() => expect(page['error']()).toContain('Référentiel incomplet.'));

      expect(page['summary']()).not.toBeNull();
    });
  });

  describe('empty state', () => {
    it('reports no hours per week and names no week when the edition covers none', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({
          nombreSemaines: 0,
          capaciteHeuresParAnimateur: 0,
          parSemaine: [],
          semaineCritique: null,
          parJour: [],
        }),
      );

      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());

      expect(page['heuresParSemaine']()).toBe(0);
      expect(page['heuresSemaineCritique']()).toBe(0);
      expect(page['semaineCritiqueLabel']()).toBe('');
    });

    it('names the missing stands rather than blaming the créneaux', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({ parJour: [], referentielsManquants: ['STANDS', 'ANIMATEURS'] }),
      );

      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());

      expect(page['referentielsManquantsLabel']()).toContain('Aucun stand');
      expect(page['referentielsManquantsLabel']()).not.toContain('Aucun créneau');
    });

    it('names the missing créneaux, and both when both are missing', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({ parJour: [], referentielsManquants: ['CRENEAUX'] }),
      );
      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());
      expect(page['referentielsManquantsLabel']()).toContain('Aucun créneau');
      expect(page['referentielsManquantsLabel']()).not.toContain('Aucun stand');

      analysesApi.staffing.mockResolvedValue(
        summary({ parJour: [], referentielsManquants: ['STANDS', 'CRENEAUX', 'ANIMATEURS'] }),
      );
      const empty = createPage();
      await vi.waitFor(() => expect(empty['summary']()).not.toBeNull());
      expect(empty['referentielsManquantsLabel']()).toContain('Aucun stand');
      expect(empty['referentielsManquantsLabel']()).toContain('Aucun créneau');
    });

    it('keeps the bounds and the day table when only the animateurs are missing', async () => {
      // The subject of the screen (issue #416): the seats depend on the stands
      // and the créneaux only, so nothing is missing from the bounds — the
      // bottleneck card is what says the comparison is.
      analysesApi.staffing.mockResolvedValue(
        summary({
          referentielsManquants: ['ANIMATEURS'],
          parCompetence: competence({ animateursTotal: 0, parTypologie: [] }),
        }),
      );

      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());

      expect(page['referentielsManquantsLabel']()).toBe('');
      expect(page['summary']()!.minimumTotal).toBe(22);
      expect(page['summary']()!.parJour).toHaveLength(1);
      expect(page['competence']()!.animateursTotal).toBe(0);
    });

    it('reports no hours per week while nothing is loaded', () => {
      analysesApi.staffing.mockReturnValue(deferred<StaffingSummary>().promise);

      const page = createPage();

      expect(page['heuresParSemaine']()).toBe(0);
      expect(page['projectionLabel']()).toBe('');
      expect(page['estBorneRetenue']('PIC_AVEC_PAUSE')).toBe(false);
    });
  });

  describe('displayed data', () => {
    // The capacity is the busiest week's own, not an event-wide total to
    // divide: a week the event barely touches offers far less than the weekly
    // ceiling, and the former division hid exactly that.
    it('reads the busiest week capacity as the server proved it, without dividing anything', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({
          capaciteHeuresParAnimateur: 20,
          nombreSemaines: 3,
          semaineCritique: semaine({
            semaine: '2026-W30',
            jours: 2,
            joursTravaillables: 2,
            heures: 96,
          }),
        }),
      );

      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());

      expect(page['heuresParSemaine']()).toBe(20);
      expect(page['heuresSemaineCritique']()).toBe(96);
      expect(page['semaineCritiqueLabel']()).toContain('2026-W30');
    });

    it('highlights the rotation bound like any other when the server retained it', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({ borneRetenue: 'ROTATION_JOURS', rotationTotal: 26 }),
      );

      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());

      expect(page['estBorneRetenue']('ROTATION_JOURS')).toBe(true);
      expect(page['estBorneRetenue']('PIC_AVEC_PAUSE')).toBe(false);
      expect(page['joursTravaillesMax']()).toBe(6);
    });

    // A projection, not a bound — so it only appears when it says something
    // the bounds do not, and never when nobody declared anything.
    it('shows the availability projection only when declared days off push it above the floor', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({
          minimumTotal: 22,
          minimumAvecIndisponibilites: 31,
          indisponibilitesDeclarees: true,
        }),
      );
      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());
      expect(page['projectionLabel']()).toContain('31');

      analysesApi.staffing.mockResolvedValue(
        summary({
          minimumTotal: 22,
          minimumAvecIndisponibilites: 22,
          indisponibilitesDeclarees: true,
        }),
      );
      page['staffing'].reload();
      await vi.waitFor(() => expect(page['projectionLabel']()).toBe(''));

      analysesApi.staffing.mockResolvedValue(
        summary({
          minimumTotal: 22,
          minimumAvecIndisponibilites: 22,
          indisponibilitesDeclarees: false,
        }),
      );
      page['staffing'].reload();
      await vi.waitFor(() => expect(analysesApi.staffing).toHaveBeenCalledTimes(3));
      expect(page['projectionLabel']()).toBe('');
    });

    // Fifth bound (issue #438): a grid with no room to eat needs more people
    // than its peak, and the screen has to say which of the five explains the
    // number — otherwise the organiser recruits without knowing why.
    it('highlights the meal-break bound when the server retained it', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({ borneRetenue: 'COUPURE_REPAS', picRepas: 14, minimumTotal: 14 }),
      );

      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());

      expect(page['estBorneRetenue']('COUPURE_REPAS')).toBe(true);
      expect(page['estBorneRetenue']('ROTATION_JOURS')).toBe(false);
      expect(page['estBorneRetenue']('PIC_SIMULTANE')).toBe(false);
    });

    it('highlights the bound the server actually retained, and only that one', async () => {
      analysesApi.staffing.mockResolvedValue(summary({ borneRetenue: 'CHARGE_HORAIRE' }));

      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());

      expect(page['estBorneRetenue']('CHARGE_HORAIRE')).toBe(true);
      expect(page['estBorneRetenue']('PIC_AVEC_PAUSE')).toBe(false);
      expect(page['estBorneRetenue']('PIC_SIMULTANE')).toBe(false);
    });

    it('names the busiest day by its number, date and open stands', () => {
      const page = createPage();

      const label = page['jourCritiqueLabel'](
        jour({ jour: 4, date: '2026-08-04', standsOuverts: 27 }),
      );

      expect(label).toContain('4');
      expect(label).toContain('2026-08-04');
      expect(label).toContain('27');
    });

    it('lists the peaks and the day minimum as columns of their own', () => {
      const page = createPage();

      expect(page['columns']).toContain('picSimultane');
      expect(page['columns']).toContain('picAvecPause');
      expect(page['columns']).toContain('minimumJour');
    });
  });

  /** Loads a page whose summary carries the given breakdown, and waits for it. */
  async function pageWith(overrides: Partial<CompetenceStaffing>): Promise<StaffingPage> {
    analysesApi.staffing.mockResolvedValue(summary({ parCompetence: competence(overrides) }));
    const page = createPage();
    await vi.waitFor(() => expect(page['competence']()).not.toBeNull());
    return page;
  }

  describe('bottleneck per game category', () => {
    // The decision this pins: one payload, one round trip. The breakdown is
    // the same computation on the same seats, so it travels with them rather
    // than through an endpoint that would rebuild the whole problem.
    it('reads the breakdown from the same payload, without a second request', async () => {
      const page = await pageWith({ polyvalents: 7 });

      expect(analysesApi.staffing).toHaveBeenCalledOnce();
      expect(page['competence']()?.polyvalents).toBe(7);
    });

    it('has no breakdown to show while nothing is loaded', () => {
      analysesApi.staffing.mockReturnValue(deferred<StaffingSummary>().promise);

      const page = createPage();

      expect(page['competence']()).toBeNull();
      expect(page['reserveLabel']()).toBe('');
    });

    it('highlights the rows the server reported a shortfall on, and only those', () => {
      const page = createPage();

      expect(page['estGoulot'](typologie({ manque: 4 }))).toBe(true);
      expect(page['estGoulot'](typologie({ manque: 0 }))).toBe(false);
    });

    it('announces the absence of a bottleneck without claiming a reserve there is none of', async () => {
      const withReserve = await pageWith({ manqueTotal: 0, polyvalents: 4 });
      expect(withReserve['reserveLabel']()).toContain('Aucun goulot');
      expect(withReserve['reserveLabel']()).toContain('4');

      const withoutReserve = await pageWith({ manqueTotal: 0, polyvalents: 0 });
      expect(withoutReserve['reserveLabel']()).toContain('Aucun goulot');
      expect(withoutReserve['reserveLabel']()).not.toContain('0 polyvalents');
    });

    it('says a reserve of zero is no notion here when no typologie is marked polyvalente', async () => {
      const page = await pageWith({ manqueTotal: 3, polyvalents: 0, typologieNinjaDefinie: false });

      expect(page['reserveLabel']()).toContain('Aucune typologie');
      expect(page['reserveLabel']()).not.toContain('plus mince');
    });

    it('reads the shortfall against the polyvalent reserve, which absorbs it or does not', async () => {
      const absorbable = await pageWith({ manqueTotal: 2, polyvalents: 5 });
      expect(absorbable['reserveLabel']()).toContain('peuvent y répondre');
      expect(absorbable['reserveLabel']()).not.toContain('plus mince');

      const shortfall = await pageWith({ manqueTotal: 8, polyvalents: 5 });
      expect(shortfall['reserveLabel']()).toContain('8');
      expect(shortfall['reserveLabel']()).toContain('plus mince');
    });

    it('reports the seats no single typologie can claim', async () => {
      const page = await pageWith({ siegesNonAttribues: 14 });

      expect(page['siegesNonAttribuesLabel']()).toContain('14');
    });

    // The opposite case of the one above, and the loud one: a stand proposing
    // nothing can only be held by a polyvalent — by nobody when the
    // referential marks none.
    it('separates the seats only polyvalents can hold from those no typologie claims', async () => {
      const withNinja = await pageWith({
        siegesReservesAuxPolyvalents: 20,
        typologieNinjaDefinie: true,
      });
      expect(withNinja['siegesReservesLabel']()).toContain('20');
      expect(withNinja['siegesReservesLabel']()).toContain('seuls les polyvalents');

      const withoutNinja = await pageWith({
        siegesReservesAuxPolyvalents: 20,
        typologieNinjaDefinie: false,
      });
      expect(withoutNinja['siegesReservesLabel']()).toContain('personne');
    });

    // Offering the reinforcements against their own shortage would promise an
    // absorption nobody can deliver.
    it('never offers the polyvalent reserve against a shortfall on the polyvalent typologie itself', async () => {
      const page = await pageWith({ manqueTotal: 2, manquePolyvalents: 2, polyvalents: 3 });

      expect(page['reserveLabel']()).toContain('rien ne peut absorber');
      expect(page['reserveLabel']()).not.toContain('peuvent y répondre');
    });
  });

  /**
   * A bottleneck names a game category nobody holds enough of; the link goes
   * to the people holding it — the list to lengthen (issue #489). A row with
   * no shortfall offers none: there is nothing to act on.
   */
  describe('the link from a bottleneck to the competent animateurs', () => {
    it('leads to the animateurs holding the typologie in shortfall, and only for those rows', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({
          parCompetence: competence({
            parTypologie: [
              typologie({ typologie: 'ESCAPE', manque: 2 }),
              typologie({ typologie: 'QUIZ', label: 'Quiz', manque: 0 }),
            ],
          }),
        }),
      );
      const fixture = TestBed.createComponent(StaffingPage);
      await fixture.whenStable();
      fixture.detectChanges();

      const root = fixture.nativeElement as HTMLElement;
      const liens = Array.from(
        root.querySelectorAll<HTMLAnchorElement>('tr.staffing-goulot a.staffing-lien'),
      );
      expect(liens.map((lien) => lien.getAttribute('href'))).toEqual([
        '/animateurs?typologie=ESCAPE',
        '/competences?typologies=ESCAPE',
      ]);
      expect(root.querySelectorAll('tr.staffing-goulot')).toHaveLength(1);
    });
  });

  /**
   * Every figure leads to the screen that changes it: a day to its stands'
   * opening hours, a bound to the settings it is proved on — and the « avant »
   * margin is a column of the days.
   */
  describe('the links from each figure to the screen that changes it', () => {
    it('links each day to its opening hours, and prints its tightest margin', async () => {
      analysesApi.margin.mockResolvedValue({
        mode: 'AVANT',
        tranches: [{ debut: '18:00:00', fin: '22:00:00' }],
        jours: [
          {
            date: '2026-09-01',
            jour: 1,
            cellules: [],
            pireCellule: {
              date: '2026-09-01',
              jour: 1,
              debut: '18:00:00',
              fin: '22:00:00',
              creneauId: 4,
              sieges: 6,
              siegesPourvus: 0,
              besoin: 6,
              disponibles: 4,
              marge: -2,
            },
          },
        ],
        animateursTotal: 4,
        cellulesDeficitaires: 1,
        pireCellule: null,
        referentielsManquants: [],
        message: '',
      });
      analysesApi.staffing.mockResolvedValue(
        summary({ parJour: [jour({ date: '2026-09-01', jour: 1 })] }),
      );
      const fixture = TestBed.createComponent(StaffingPage);
      await fixture.whenStable();
      fixture.detectChanges();

      const root = fixture.nativeElement as HTMLElement;
      const hrefs = Array.from(root.querySelectorAll<HTMLAnchorElement>('a')).map((lien) =>
        lien.getAttribute('href'),
      );
      expect(hrefs).toContain('/ouvertures?du=2026-09-01&au=2026-09-01');
      expect(hrefs).toContain('/regles?onglet=legal&regle=coupureRepasObligatoire');
      expect(hrefs).toContain('/regles?onglet=legal&regle=maxJoursConsecutifsTravaillesDur');
      expect(root.querySelector('.staffing-marge')?.textContent).toBe('-2');
      expect(root.querySelector('.staffing-marge-tranche')?.textContent).toBe('18:00-22:00');
      expect(root.textContent).toContain('Horaires des stands du 01/09');
    });

    // « À former » starts where the tables above it end: scrolled to while
    // they still load, the reader would land on what they push down. And the
    // landing is obeyed once — coming back to the tab does not scroll again.
    it('scrolls to « À former » once the figures above it are on screen, then drops `section`', async () => {
      const pending = deferred<StaffingSummary>();
      analysesApi.staffing.mockReturnValue(pending.promise);
      const scroll = vi.fn();
      const original = Element.prototype.scrollIntoView;
      Element.prototype.scrollIntoView = scroll;
      try {
        await TestBed.inject(Router).navigateByUrl('/?onglet=besoin&section=former');
        const fixture = TestBed.createComponent(StaffingPage);
        fixture.detectChanges();
        await new Promise((resolve) => setTimeout(resolve));
        fixture.detectChanges();
        expect(scroll).not.toHaveBeenCalled();

        pending.resolve(summary());
        await vi.waitFor(() => {
          fixture.detectChanges();
          expect(scroll).toHaveBeenCalledTimes(1);
        });
        expect(scroll.mock.contexts[0]).toBe(document.getElementById('a-former'));
        const location = TestBed.inject(Location);
        await vi.waitFor(() => expect(location.path()).not.toContain('section'));
        expect(location.path()).toContain('onglet=besoin');
      } finally {
        Element.prototype.scrollIntoView = original;
      }
    });

    it('ends on « À former », the section `?onglet=former` lands on', async () => {
      const fixture = TestBed.createComponent(StaffingPage);
      await fixture.whenStable();
      fixture.detectChanges();

      const section = (fixture.nativeElement as HTMLElement).querySelector('#a-former')!;
      expect(section.querySelector('h2')?.textContent).toContain('À former');
      expect(section.querySelector('app-formation-page')).not.toBeNull();
    });
  });

  /**
   * The previous edition's measure, beside each game category and for
   * information only: matched by name, never by id alone; absent, no column
   * at all.
   */
  describe("the previous edition's realised", () => {
    it('adds a column only when an earlier edition left a measure', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({
          parCompetence: competence({
            parTypologie: [
              typologie({ typologie: 'ESCAPE', manque: 0 }),
              typologie({ typologie: 'T42', label: 'Quiz', manque: 0 }),
            ],
          }),
        }),
      );
      const fixture = TestBed.createComponent(StaffingPage);
      await fixture.whenStable();
      fixture.detectChanges();
      const root = fixture.nativeElement as HTMLElement;
      expect(root.textContent).not.toContain('Édition précédente');

      realiseApi.previousEdition.mockResolvedValue({
        ...NO_PREVIOUS_EDITION,
        available: true,
        editionId: 'E1',
        editionNom: 'Année 2025',
        byTypologie: [
          { key: 'ESCAPE', label: 'Escape game', counts: counts(20, 5, 90) },
          // Another edition's id for the same name: read by its name.
          { key: 'T7', label: 'quiz', counts: counts(10, 0, 0) },
        ],
      });
      const withMeasure = TestBed.createComponent(StaffingPage);
      await withMeasure.whenStable();
      withMeasure.detectChanges();

      const cellules = Array.from(
        (withMeasure.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
          'td.mat-column-precedente',
        ),
      ).map((cellule) => cellule.textContent?.trim() ?? '');
      expect(cellules).toHaveLength(2);
      expect(cellules[0]).toMatch(/25\s?%/);
      expect(cellules[0]).toContain('1,5 h');
      expect(cellules[1]).toMatch(/0\s?%/);
    });

    it('never reads a category of last year by its id alone', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({
          parCompetence: competence({
            parTypologie: [typologie({ typologie: 'T1', label: 'Stratégie', manque: 0 })],
          }),
        }),
      );
      realiseApi.previousEdition.mockResolvedValue({
        ...NO_PREVIOUS_EDITION,
        available: true,
        editionId: 'E1',
        editionNom: 'Année 2025',
        // Last year's T1 was another category: its counter is no name.
        byTypologie: [{ key: 'T1', label: 'Escape game', counts: counts(20, 5, 90) }],
      });
      const fixture = TestBed.createComponent(StaffingPage);
      await fixture.whenStable();
      fixture.detectChanges();

      const cellule = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
        'td.mat-column-precedente',
      );
      expect(cellule?.textContent?.trim()).toBe('—');
    });
  });

  describe('the recruitment brief', () => {
    it('shows each typologie minimum before any animateur, without the pool columns', async () => {
      const before = await pageWith({ animateursTotal: 0 });
      expect(before['competenceColumns']()).toEqual(['typologie', 'sieges', 'minimumTotal']);

      const after = await pageWith({ animateursTotal: 12 });
      expect(after['competenceColumns']()).toContain('specialistes');
      expect(after['competenceColumns']()).toContain('manque');
    });

    it('says how many typologies each recruit must carry once the rows add up past the floor', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({ minimumTotal: 20, parCompetence: competence({ planchersCumules: 30 }) }),
      );
      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());

      expect(page['cumulLabel']()).toContain('30');
      expect(page['cumulLabel']()).toContain('20');
      expect(page['cumulLabel']()).toContain('1.5');

      analysesApi.staffing.mockResolvedValue(
        summary({ minimumTotal: 20, parCompetence: competence({ planchersCumules: 20 }) }),
      );
      page['staffing'].reload();
      await vi.waitFor(() => expect(page['cumulLabel']()).toBe(''));
    });

    it('names the cap on days in a row the sequence bound was proved under', async () => {
      analysesApi.staffing.mockResolvedValue(summary({ joursConsecutifsMax: 6 }));
      const page = createPage();
      await vi.waitFor(() => expect(page['consecutiveCapLabel']()).toContain('6'));
    });

    it('flags a week and a day whose declared days off go beyond what they absorb', async () => {
      const page = await pageWith({ animateursTotal: 40 });

      expect(
        page['isWeekOverBudget'](semaine({ budgetIndisponibilites: 2, indisponibilitesAuDela: 3 })),
      ).toBe(true);
      expect(
        page['isWeekOverBudget'](semaine({ budgetIndisponibilites: 2, indisponibilitesAuDela: 2 })),
      ).toBe(false);
      // 40 known, 35 free that day: five away for four tolerated.
      expect(page['isDayOverTolerance'](jour({ disponibles: 35, absentsMax: 4 }))).toBe(true);
      expect(page['isDayOverTolerance'](jour({ disponibles: 36, absentsMax: 4 }))).toBe(false);
    });
  });

  describe('the check by a solve', () => {
    it('starts a check of the team and time typed in, and follows it until it ends', async () => {
      vi.useFakeTimers();
      try {
        analysesApi.verifyStaffing.mockResolvedValue(
          verification({ effectif: 25, majeurs: 20, mineurs: 5 }),
        );
        analysesApi.staffingVerification.mockResolvedValue(null);
        // No call by hand: the zoneless test bed runs ngOnInit on its own first check.
        const page = createPage();
        await vi.waitFor(() => expect(analysesApi.staffingVerification).toHaveBeenCalledOnce());

        page['setAdults']('20');
        page['setMinors']('5');
        page['setSeconds']('120');
        await page['lancerVerification']();

        expect(analysesApi.verifyStaffing).toHaveBeenCalledWith({
          majeurs: 20,
          mineurs: 5,
          dureeSecondes: 120,
        });
        expect(page['verification']()?.etat).toBe('EN_COURS');
        expect(page['verificationLabel']()).toBe('');
        expect(page['verificationTeam']()).toBe('25 personnes (20 majeurs, 5 mineurs)');

        analysesApi.staffingVerification.mockResolvedValue(
          verification({
            effectif: 25,
            majeurs: 20,
            mineurs: 5,
            etat: 'TERMINEE',
            realisable: true,
            dureeSecondes: 42,
            siegesNonPourvus: 0,
          }),
        );
        await vi.advanceTimersByTimeAsync(3000);

        expect(page['verification']()?.etat).toBe('TERMINEE');
        expect(page['verificationLabel']()).toContain('25 personnes (20 majeurs, 5 mineurs)');
        expect(page['verificationLabel']()).toContain('42');
      } finally {
        vi.useRealTimers();
      }
    });

    it('stops following the check once the screen is left, even with a read in flight', async () => {
      vi.useFakeTimers();
      try {
        const pending = deferred<StaffingVerification | null>();
        analysesApi.staffingVerification.mockReturnValueOnce(pending.promise);
        const fixture = TestBed.createComponent(StaffingPage);
        fixture.componentInstance.ngOnInit();

        fixture.destroy();
        pending.resolve(verification({ etat: 'EN_COURS' }));
        await vi.advanceTimersByTimeAsync(10000);

        expect(analysesApi.staffingVerification).toHaveBeenCalledOnce();
      } finally {
        vi.useRealTimers();
      }
    });

    it('shows, in an empty « Majeurs » field, the adults the server will check: the floor less the minors', async () => {
      analysesApi.staffing.mockResolvedValue(summary({ minimumTotal: 150 }));
      const page = createPage();
      await vi.waitFor(() => expect(page['summary']()).not.toBeNull());

      expect(page['adultsPlaceholder']()).toBe('150');
      page['setMinors']('10');
      expect(page['adultsPlaceholder']()).toBe('140');
      page['setMinors']('400');
      expect(page['adultsPlaceholder']()).toBe('0');
    });

    it('shows the instance quota refusal as the server words it, and follows nothing', async () => {
      const quota =
        'Cette instance limite le calcul à 12 résolutions par heure. Prochaine résolution possible à 14:32.';
      // What the API layer throws on that 409: its sentence, nothing to switch on.
      analysesApi.verifyStaffing.mockRejectedValue(
        toError(new HttpErrorResponse({ status: 409, error: { message: quota } })),
      );
      const page = createPage();

      await page['lancerVerification']();

      expect(page['verificationErreur']()).toBe(quota);
      expect(page['verification']()).toBeNull();
    });

    it('checks the floor when nothing is typed in, and never calls a failure a proof', async () => {
      analysesApi.verifyStaffing.mockResolvedValue(
        verification({
          etat: 'TERMINEE',
          realisable: false,
          siegesNonPourvus: 3,
          dureeSecondes: 600,
          reglesEnDefaut: ['posteDoitEtrePourvu'],
        }),
      );
      const page = createPage();

      page['setAdults']('');
      await page['lancerVerification']();

      expect(analysesApi.verifyStaffing).toHaveBeenCalledWith({
        majeurs: null,
        mineurs: null,
        dureeSecondes: null,
      });
      expect(page['verificationLabel']()).toContain('3');
      expect(page['verificationLabel']()).toContain('pas une preuve');
    });
  });
});

/** The counters of a previous measure: published seats, absences, lost minutes. */
function counts(publies: number, absences: number, minutesPerdues: number) {
  return {
    publishedSeats: publies,
    keptSeats: publies,
    absences,
    replacements: 0,
    emptySeats: 0,
    removedSeats: 0,
    addedSeats: 0,
    publishedMinutes: publies * 120,
    realisedMinutes: publies * 120 - minutesPerdues,
    lostMinutes: minutesPerdues,
    absenceRate: publies === 0 ? null : absences / publies,
    replacementRate: publies === 0 ? null : 0,
  };
}

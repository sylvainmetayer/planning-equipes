// Loading / error / empty / data states of the staffing-need screen, plus the
// derived values the template binds to. The component is created but never
// rendered, so this stays a logic test (the project favours those over full
// DOM rendering).
//
// These four states are exactly what a migration to `httpResource()` would
// re-implement, which is why they are pinned here first.

import { provideZonelessChangeDetection, Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import {
  BorneStaffing,
  CompetenceStaffing,
  JourStaffing,
  SemaineStaffing,
  StaffingSummary,
  TypologieStaffing,
} from '../../core/models';
import { StaffingPage } from './staffing-page';

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
    minimumMajeurs: 15,
    minimumMineurs: 7,
    dureeHebdomadaireMaxMinutes: 2880,
    dureeQuotidienneMaxMinutes: 600,
    joursTravaillesMaxParSemaine: 6,
    parCompetence: competence(),
    referentielsManquants: [],
    ...overrides,
  };
}

/** Reaches the protected members the template binds to. */
type PageInternals = {
  summary: Signal<StaffingSummary | null>;
  loading: Signal<boolean>;
  error: Signal<string>;
  columns: string[];
  heuresParSemaine: Signal<number>;
  heuresSemaineCritique: Signal<number>;
  projectionLabel: Signal<string>;
  jourCritiqueLabel: (jour: JourStaffing) => string;
  semaineCritiqueLabel: () => string;
  estBorneRetenue: (borne: BorneStaffing) => boolean;
  pauseMinutes: () => number;
  joursTravaillesMax: () => number;
  competenceColumns: string[];
  competence: Signal<CompetenceStaffing | null>;
  estGoulot: (ligne: TypologieStaffing) => boolean;
  reserveLabel: Signal<string>;
  siegesNonAttribuesLabel: Signal<string>;
  siegesReservesLabel: Signal<string>;
  referentielsManquantsLabel: Signal<string>;
  /** Private to the component; reachable here because `private` is compile-time only. */
  staffing: { reload(): boolean };
};

function createPage(): PageInternals {
  return TestBed.createComponent(StaffingPage).componentInstance as unknown as PageInternals;
}

describe('StaffingPage', () => {
  const analysesApi = { staffing: vi.fn() };

  beforeEach(() => {
    analysesApi.staffing.mockReset();
    analysesApi.staffing.mockResolvedValue(summary());
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AnalysesApi, useValue: analysesApi },
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

      expect(page.loading()).toBe(true);
      expect(page.summary()).toBeNull();
    });

    it('stops loading once the summary lands', async () => {
      const pending = deferred<StaffingSummary>();
      analysesApi.staffing.mockReturnValue(pending.promise);
      const page = createPage();

      pending.resolve(summary({ minimumTotal: 33 }));
      await vi.waitFor(() => expect(page.loading()).toBe(false));

      expect(page.summary()?.minimumTotal).toBe(33);
    });

    it('reads the summary from the server on creation instead of computing it in the browser', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.loading()).toBe(false));

      expect(analysesApi.staffing).toHaveBeenCalledOnce();
    });
  });

  describe('error state', () => {
    it('shows the failure message and stops loading', async () => {
      analysesApi.staffing.mockRejectedValue(new Error('Référentiel incomplet.'));

      const page = createPage();
      await vi.waitFor(() => expect(page.loading()).toBe(false));

      expect(page.error()).toContain('Référentiel incomplet.');
      expect(page.summary()).toBeNull();
    });

    it('clears the error once a later load succeeds', async () => {
      analysesApi.staffing.mockRejectedValueOnce(new Error('Référentiel incomplet.'));
      const page = createPage();
      await vi.waitFor(() => expect(page.error()).not.toBe(''));

      analysesApi.staffing.mockResolvedValue(summary());
      page.staffing.reload();
      await vi.waitFor(() => expect(page.error()).toBe(''));

      expect(page.summary()).not.toBeNull();
    });

    // Deliberate, and different from the hours screen: the error is cleared on
    // success, never when a load starts. A failing refresh therefore keeps the
    // previous summary on screen behind its message rather than blanking it.
    it('keeps the previous summary visible when a refresh fails', async () => {
      const page = createPage();
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      analysesApi.staffing.mockRejectedValue(new Error('Référentiel incomplet.'));
      page.staffing.reload();
      await vi.waitFor(() => expect(page.error()).toContain('Référentiel incomplet.'));

      expect(page.summary()).not.toBeNull();
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
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      expect(page.heuresParSemaine()).toBe(0);
      expect(page.heuresSemaineCritique()).toBe(0);
      expect(page.semaineCritiqueLabel()).toBe('');
    });

    it('names the missing stands rather than blaming the créneaux', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({ parJour: [], referentielsManquants: ['STANDS', 'ANIMATEURS'] }),
      );

      const page = createPage();
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      expect(page.referentielsManquantsLabel()).toContain('Aucun stand');
      expect(page.referentielsManquantsLabel()).not.toContain('Aucun créneau');
    });

    it('names the missing créneaux, and both when both are missing', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({ parJour: [], referentielsManquants: ['CRENEAUX'] }),
      );
      const page = createPage();
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());
      expect(page.referentielsManquantsLabel()).toContain('Aucun créneau');
      expect(page.referentielsManquantsLabel()).not.toContain('Aucun stand');

      analysesApi.staffing.mockResolvedValue(
        summary({ parJour: [], referentielsManquants: ['STANDS', 'CRENEAUX', 'ANIMATEURS'] }),
      );
      const empty = createPage();
      await vi.waitFor(() => expect(empty.summary()).not.toBeNull());
      expect(empty.referentielsManquantsLabel()).toContain('Aucun stand');
      expect(empty.referentielsManquantsLabel()).toContain('Aucun créneau');
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
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      expect(page.referentielsManquantsLabel()).toBe('');
      expect(page.summary()!.minimumTotal).toBe(22);
      expect(page.summary()!.parJour).toHaveLength(1);
      expect(page.competence()!.animateursTotal).toBe(0);
    });

    it('reports no hours per week while nothing is loaded', () => {
      analysesApi.staffing.mockReturnValue(deferred<StaffingSummary>().promise);

      const page = createPage();

      expect(page.heuresParSemaine()).toBe(0);
      expect(page.projectionLabel()).toBe('');
      expect(page.estBorneRetenue('PIC_AVEC_PAUSE')).toBe(false);
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
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      expect(page.heuresParSemaine()).toBe(20);
      expect(page.heuresSemaineCritique()).toBe(96);
      expect(page.semaineCritiqueLabel()).toContain('2026-W30');
    });

    it('highlights the rotation bound like any other when the server retained it', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({ borneRetenue: 'ROTATION_JOURS', rotationTotal: 26 }),
      );

      const page = createPage();
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      expect(page.estBorneRetenue('ROTATION_JOURS')).toBe(true);
      expect(page.estBorneRetenue('PIC_AVEC_PAUSE')).toBe(false);
      expect(page.joursTravaillesMax()).toBe(6);
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
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());
      expect(page.projectionLabel()).toContain('31');

      analysesApi.staffing.mockResolvedValue(
        summary({
          minimumTotal: 22,
          minimumAvecIndisponibilites: 22,
          indisponibilitesDeclarees: true,
        }),
      );
      page.staffing.reload();
      await vi.waitFor(() => expect(page.projectionLabel()).toBe(''));

      analysesApi.staffing.mockResolvedValue(
        summary({
          minimumTotal: 22,
          minimumAvecIndisponibilites: 22,
          indisponibilitesDeclarees: false,
        }),
      );
      page.staffing.reload();
      await vi.waitFor(() => expect(analysesApi.staffing).toHaveBeenCalledTimes(3));
      expect(page.projectionLabel()).toBe('');
    });

    // Fifth bound (issue #438): a grid with no room to eat needs more people
    // than its peak, and the screen has to say which of the five explains the
    // number — otherwise the organiser recruits without knowing why.
    it('highlights the meal-break bound when the server retained it', async () => {
      analysesApi.staffing.mockResolvedValue(
        summary({ borneRetenue: 'COUPURE_REPAS', picRepas: 14, minimumTotal: 14 }),
      );

      const page = createPage();
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      expect(page.estBorneRetenue('COUPURE_REPAS')).toBe(true);
      expect(page.estBorneRetenue('ROTATION_JOURS')).toBe(false);
      expect(page.estBorneRetenue('PIC_SIMULTANE')).toBe(false);
    });

    it('highlights the bound the server actually retained, and only that one', async () => {
      analysesApi.staffing.mockResolvedValue(summary({ borneRetenue: 'CHARGE_HORAIRE' }));

      const page = createPage();
      await vi.waitFor(() => expect(page.summary()).not.toBeNull());

      expect(page.estBorneRetenue('CHARGE_HORAIRE')).toBe(true);
      expect(page.estBorneRetenue('PIC_AVEC_PAUSE')).toBe(false);
      expect(page.estBorneRetenue('PIC_SIMULTANE')).toBe(false);
    });

    it('names the busiest day by its number, date and open stands', () => {
      const page = createPage();

      const label = page.jourCritiqueLabel(
        jour({ jour: 4, date: '2026-08-04', standsOuverts: 27 }),
      );

      expect(label).toContain('4');
      expect(label).toContain('2026-08-04');
      expect(label).toContain('27');
    });

    it('lists the peaks and the day minimum as columns of their own', () => {
      const page = createPage();

      expect(page.columns).toContain('picSimultane');
      expect(page.columns).toContain('picAvecPause');
      expect(page.columns).toContain('minimumJour');
    });
  });

  /** Loads a page whose summary carries the given breakdown, and waits for it. */
  async function pageWith(overrides: Partial<CompetenceStaffing>): Promise<PageInternals> {
    analysesApi.staffing.mockResolvedValue(summary({ parCompetence: competence(overrides) }));
    const page = createPage();
    await vi.waitFor(() => expect(page.competence()).not.toBeNull());
    return page;
  }

  describe('bottleneck per game category', () => {
    // The decision this pins: one payload, one round trip. The breakdown is
    // the same computation on the same seats, so it travels with them rather
    // than through an endpoint that would rebuild the whole problem.
    it('reads the breakdown from the same payload, without a second request', async () => {
      const page = await pageWith({ polyvalents: 7 });

      expect(analysesApi.staffing).toHaveBeenCalledOnce();
      expect(page.competence()?.polyvalents).toBe(7);
    });

    it('has no breakdown to show while nothing is loaded', () => {
      analysesApi.staffing.mockReturnValue(deferred<StaffingSummary>().promise);

      const page = createPage();

      expect(page.competence()).toBeNull();
      expect(page.reserveLabel()).toBe('');
    });

    it('highlights the rows the server reported a shortfall on, and only those', () => {
      const page = createPage();

      expect(page.estGoulot(typologie({ manque: 4 }))).toBe(true);
      expect(page.estGoulot(typologie({ manque: 0 }))).toBe(false);
    });

    it('announces the absence of a bottleneck without claiming a reserve there is none of', async () => {
      const withReserve = await pageWith({ manqueTotal: 0, polyvalents: 4 });
      expect(withReserve.reserveLabel()).toContain('Aucun goulot');
      expect(withReserve.reserveLabel()).toContain('4');

      const withoutReserve = await pageWith({ manqueTotal: 0, polyvalents: 0 });
      expect(withoutReserve.reserveLabel()).toContain('Aucun goulot');
      expect(withoutReserve.reserveLabel()).not.toContain('0 polyvalents');
    });

    it('says a reserve of zero is no notion here when no typologie is marked polyvalente', async () => {
      const page = await pageWith({ manqueTotal: 3, polyvalents: 0, typologieNinjaDefinie: false });

      expect(page.reserveLabel()).toContain('Aucune typologie');
      expect(page.reserveLabel()).not.toContain('plus mince');
    });

    it('reads the shortfall against the polyvalent reserve, which absorbs it or does not', async () => {
      const absorbable = await pageWith({ manqueTotal: 2, polyvalents: 5 });
      expect(absorbable.reserveLabel()).toContain('peuvent y répondre');
      expect(absorbable.reserveLabel()).not.toContain('plus mince');

      const shortfall = await pageWith({ manqueTotal: 8, polyvalents: 5 });
      expect(shortfall.reserveLabel()).toContain('8');
      expect(shortfall.reserveLabel()).toContain('plus mince');
    });

    it('reports the seats no single typologie can claim', async () => {
      const page = await pageWith({ siegesNonAttribues: 14 });

      expect(page.siegesNonAttribuesLabel()).toContain('14');
    });

    // The opposite case of the one above, and the loud one: a stand proposing
    // nothing can only be held by a polyvalent — by nobody when the
    // referential marks none.
    it('separates the seats only polyvalents can hold from those no typologie claims', async () => {
      const withNinja = await pageWith({
        siegesReservesAuxPolyvalents: 20,
        typologieNinjaDefinie: true,
      });
      expect(withNinja.siegesReservesLabel()).toContain('20');
      expect(withNinja.siegesReservesLabel()).toContain('seuls les polyvalents');

      const withoutNinja = await pageWith({
        siegesReservesAuxPolyvalents: 20,
        typologieNinjaDefinie: false,
      });
      expect(withoutNinja.siegesReservesLabel()).toContain('personne');
    });

    // Offering the reinforcements against their own shortage would promise an
    // absorption nobody can deliver.
    it('never offers the polyvalent reserve against a shortfall on the polyvalent typologie itself', async () => {
      const page = await pageWith({ manqueTotal: 2, manquePolyvalents: 2, polyvalents: 3 });

      expect(page.reserveLabel()).toContain('rien ne peut absorber');
      expect(page.reserveLabel()).not.toContain('peuvent y répondre');
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

      const liens = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>(
          'a.staffing-lien',
        ),
      );
      expect(liens.map((lien) => lien.getAttribute('href'))).toEqual([
        '/animateurs?typologie=ESCAPE',
      ]);
    });
  });
});

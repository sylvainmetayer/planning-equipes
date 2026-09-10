// The live score curve (issue #304). The card decides two things about it,
// and both are about not lying: whether the card is on screen at all, and
// whether the curve on hand really describes the run being reported.

import { provideZonelessChangeDetection, Signal, WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ScorePoint, ScoreTrace } from '../../core/models';
import { SolverJobService, TrackedJob } from '../../core/solver-job.service';
import { SCORE_CURVE_STORAGE_KEY, ScoreCurveCard } from './score-curve-card';

type CardInternals = {
  trace: Signal<ScoreTrace | null>;
  points: Signal<ScorePoint[]>;
  visible: Signal<boolean>;
  folded: WritableSignal<boolean>;
  toggle: () => void;
};

function tracked(overrides: Partial<TrackedJob> = {}): TrackedJob {
  return {
    id: 'j1',
    type: 'SOLVE',
    label: 'Résolution',
    startedAtMs: Date.now(),
    mine: true,
    editionId: 'festival-2026',
    editionNom: 'Festival 2026',
    secondsLimit: 600,
    ...overrides
  };
}

const trace = (overrides: Partial<ScoreTrace> = {}): ScoreTrace => ({
  jobId: 'job-1',
  editionId: 'festival-2026',
  generation: 1,
  intervalleMs: 1000,
  dureeMs: 30000,
  termine: false,
  points: [{ tempsMs: 0, hard: -40, medium: -10, soft: -1000 }],
  ...overrides
});

describe('ScoreCurveCard', () => {
  const activeJob = signal<TrackedJob | null>(null);
  const editingLocked = signal(false);
  /** The live score curve, already narrowed to this edition by the service. */
  const scoreTraceEdition = signal<ScoreTrace | null>(null);
  const jobs = {
    activeJob,
    editingLocked: () => editingLocked(),
    scoreTraceEdition,
    chargerCourbeScore: vi.fn(async () => undefined)
  };

  beforeEach(() => {
    activeJob.set(null);
    editingLocked.set(false);
    scoreTraceEdition.set(null);
    jobs.chargerCourbeScore.mockClear();
    // The folded state is a real localStorage preference: clear it, or one
    // test's fold decides the next one's opening state.
    localStorage.removeItem(SCORE_CURVE_STORAGE_KEY);
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: SolverJobService, useValue: jobs }]
    });
  });

  function createCard(): CardInternals {
    return TestBed.createComponent(ScoreCurveCard).componentInstance as unknown as CardInternals;
  }

  it('shows nothing at all when no solve has ever run', () => {
    const card = createCard();

    expect(card.visible()).toBe(false);
    expect(card.trace()).toBeNull();
  });

  it('keeps the curve of the last run up once it is over', () => {
    // Out of scope is replaying PAST solves; the one that just finished is
    // still the answer to "was it worth waiting?", so it stays on screen
    // until the next run replaces it.
    scoreTraceEdition.set(trace({ termine: true }));
    const card = createCard();

    expect(card.visible()).toBe(true);
    expect(card.points()).toHaveLength(1);
  });

  it('brings the card up as soon as a solve starts on this edition', () => {
    // Before its first point: the card has to appear when the solve does,
    // not a few seconds later when the solver announces a first solution.
    activeJob.set(tracked({ id: 'job-2' }));
    editingLocked.set(true);
    const card = createCard();

    expect(card.visible()).toBe(true);
    expect(card.points()).toEqual([]);
  });

  it('drops the previous run’s curve the moment another job takes the solver', () => {
    // The lie this guards against: a new job is reported as running while the
    // curve still on hand is the previous one's, so its points read as this
    // run's progress.
    scoreTraceEdition.set(trace({ jobId: 'job-1', termine: true }));
    activeJob.set(tracked({ id: 'job-2' }));
    editingLocked.set(true);
    const card = createCard();

    expect(card.trace()).toBeNull();
    expect(card.visible()).toBe(true);
  });

  it('leaves the card out for a solve running on another edition', () => {
    // `scoreTraceEdition` is already null there (the service narrows it), and
    // `editingLocked` is false because that run does not freeze this edition.
    activeJob.set(tracked({ id: 'job-2', editionId: 'festival-2025' }));
    editingLocked.set(false);
    const card = createCard();

    expect(card.visible()).toBe(false);
  });

  it('reads the curve once when it appears', () => {
    createCard();

    // The one read carrying an edition header, hence the only one the server
    // can refuse — see SolverJobService.chargerCourbeScore.
    expect(jobs.chargerCourbeScore).toHaveBeenCalledTimes(1);
  });

  /**
   * Folding it away (retour utilisateur, #333) — three charts are a lot of
   * screen for someone who launched a fifteen-minute solve and left the room.
   */
  describe('folding it away', () => {
    it('starts unfolded, and remembers the fold across visits', () => {
      const card = createCard();
      expect(card.folded()).toBe(false);

      card.toggle();

      expect(card.folded()).toBe(true);
      // Written where the drawer writes its own folded groups: a fold the
      // next visit forgets is a gesture to make again on every load.
      expect(localStorage.getItem(SCORE_CURVE_STORAGE_KEY)).toBe('true');
      // And a card rebuilt (a navigation, a reload) comes back folded.
      expect(createCard().folded()).toBe(true);
    });

    it('unfolds again, and stops remembering', () => {
      localStorage.setItem(SCORE_CURVE_STORAGE_KEY, 'true');
      const card = createCard();

      card.toggle();

      expect(card.folded()).toBe(false);
      expect(localStorage.getItem(SCORE_CURVE_STORAGE_KEY)).toBe('false');
    });

    it('keeps the whole curve while folded, rather than a hole', () => {
      // The property that matters, and the one that would quietly break:
      // folding is a rendering choice, so nothing may stop the recording.
      // Reopened, the panel must show the run from its first point — that
      // history is the entire reason the curve exists.
      scoreTraceEdition.set(trace());
      const card = createCard();
      card.toggle();

      expect(card.points()).toHaveLength(1);
      expect(card.visible()).toBe(true);
      // Points that landed while folded are held just the same.
      scoreTraceEdition.set(
        trace({ points: [{ tempsMs: 0, hard: -40, medium: -10, soft: -1000 }, { tempsMs: 1000, hard: 0, medium: -6, soft: -800 }] })
      );
      expect(card.points()).toHaveLength(2);

      card.toggle();
      expect(card.folded()).toBe(false);
      expect(card.points()).toHaveLength(2);
    });
  });
});
